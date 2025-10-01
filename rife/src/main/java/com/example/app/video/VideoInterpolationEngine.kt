package com.example.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class InterpolationResult(
    val outputUri: Uri?,
    val averageMsPerInterpolatedFrame: Double,
    val doubledFps: Double,
    val error: String? = null
)

class VideoInterpolationEngine(
    private val context: Context,
    private val interpolator: FrameInterpolator
) {
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    suspend fun run(sourceVideo: Uri): InterpolationResult = withContext(Dispatchers.IO) {
        val cacheRoot = File(context.cacheDir, "interp").apply { mkdirs() }
        val framesDir = File(cacheRoot, "frames").apply { deleteRecursively(); mkdirs() }
        val outDir = File(cacheRoot, "out").apply { deleteRecursively(); mkdirs() }

        // 1) Extract all frames using ffmpeg
        val inputPath = FileUtils.getPathForUri(context, sourceVideo) ?: return@withContext InterpolationResult(
            null, 0.0, 0.0, "Could not resolve source URI"
        )
        val extractCmd = "-y -i \"$inputPath\" -vsync 0 \"${framesDir.absolutePath}/frame_%06d.png\""
        val extractSession = FFmpegKit.execute(extractCmd)
        if (!ReturnCode.isSuccess(extractSession.returnCode)) {
            return@withContext InterpolationResult(null, 0.0, 0.0, "FFmpeg extract failed: ${extractSession.failStackTrace}")
        }

        val frameFiles = framesDir.listFiles { f -> f.extension.equals("png", true) }?.sortedBy { it.name } ?: emptyList()
        if (frameFiles.size < 2) {
            return@withContext InterpolationResult(null, 0.0, 0.0, "Not enough frames (${frameFiles.size})")
        }

        // Get duration for fps estimation
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, sourceVideo)
        val durationMs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
        retriever.release()
        val origFps = if (durationMs > 0) (frameFiles.size.toDouble() / (durationMs.toDouble() / 1000.0)) else 30.0
        val targetFps = origFps * 2.0

        // 2) Interpolate frame between each consecutive pair
        val doubledFramesDir = File(outDir, "frames").apply { mkdirs() }
        var totalInterpMs = 0L
        var interpCount = 0
        for (i in 0 until frameFiles.lastIndex) {
            val a = frameFiles[i]
            val b = frameFiles[i + 1]

            val aBmp = BitmapUtils.decodePng(a)
            val bBmp = BitmapUtils.decodePng(b)
            // Write original A
            val outA = File(doubledFramesDir, String.format("frame_%06d.png", (i * 2) + 1))
            BitmapUtils.writePng(aBmp, outA)

            val start = System.nanoTime()
            val mid = interpolator.interpolateFrame(aBmp, bBmp)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            totalInterpMs += elapsedMs
            interpCount += 1

            // Write interpolated
            val outMid = File(doubledFramesDir, String.format("frame_%06d.png", (i * 2) + 2))
            BitmapUtils.writePng(mid, outMid)

            aBmp.recycle()
            bBmp.recycle()
            mid.recycle()

            _progress.value = (i + 1).toFloat() / (frameFiles.size - 1).toFloat()
        }
        // Append last original frame
        val last = frameFiles.last()
        val lastBmp = BitmapUtils.decodePng(last)
        val outLast = File(doubledFramesDir, String.format("frame_%06d.png", frameFiles.lastIndex * 2 + 1))
        BitmapUtils.writePng(lastBmp, outLast)
        lastBmp.recycle()

        // 3) Encode sequence directly (image2) at doubled fps
        val outputFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "interpolated_${System.currentTimeMillis()}.mp4")
        val pattern = File(doubledFramesDir, "frame_%06d.png").absolutePath
        // Prefer libx264, but fall back to platform encoders if not present
        val encodeCmd = "-y -framerate ${"%.3f".format(targetFps)} -i \"$pattern\" -c:v libx264 -pix_fmt yuv420p \"${outputFile.absolutePath}\""
        val encodeSession = FFmpegKit.execute(encodeCmd)
        if (!ReturnCode.isSuccess(encodeSession.returnCode)) {
            // Try common fallbacks: mpeg4 (software), or h264_mediacodec if available
            val fallback1 = "-y -framerate ${"%.3f".format(targetFps)} -i \"$pattern\" -c:v mpeg4 -q:v 2 \"${outputFile.absolutePath}\""
            val fb1 = FFmpegKit.execute(fallback1)
            if (!ReturnCode.isSuccess(fb1.returnCode)) {
                val fallback2 = "-y -framerate ${"%.3f".format(targetFps)} -i \"$pattern\" -c:v h264_mediacodec -pix_fmt yuv420p \"${outputFile.absolutePath}\""
                val fb2 = FFmpegKit.execute(fallback2)
                if (!ReturnCode.isSuccess(fb2.returnCode)) {
                    return@withContext InterpolationResult(null, totalInterpMs.toDouble() / (interpCount.coerceAtLeast(1)), targetFps, "FFmpeg encode failed: ${fb2.failStackTrace}")
                }
            }
        }

        val avg = totalInterpMs.toDouble() / interpCount.coerceAtLeast(1)
        InterpolationResult(Uri.fromFile(outputFile), avg, targetFps, null)
    }
}

object BitmapUtils {
    fun decodePng(file: File): Bitmap {
        return android.graphics.BitmapFactory.decodeFile(file.absolutePath)!!
    }

    fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}

object FileUtils {
    fun getPathForUri(context: Context, uri: Uri): String? {
        // Try direct path if it's a file scheme
        if (uri.scheme == "file") return uri.path
        // For SAF content Uris, copy to a temp file for FFmpeg
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val tmp = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
            tmp.outputStream().use { out ->
                input.copyTo(out)
            }
            tmp.absolutePath
        } catch (t: Throwable) {
            null
        }
    }
}



