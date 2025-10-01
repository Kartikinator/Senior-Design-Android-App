package com.example.app.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// SNPE Java API (provided by your AAR in app/libs)
import com.qualcomm.qti.snpe.SNPE
import com.qualcomm.qti.snpe.NeuralNetwork
import com.qualcomm.qti.snpe.ITensor

class SnpeFrameInterpolator(
    private val appContext: Context,
    private val dlcUri: Uri?
) : FrameInterpolator {
    @Volatile
    private var network: NeuralNetwork? = null

    // IO names and sizes (update for your DLC)
    private val inputName0 = "I0"
    private val inputName1 = "I1"
    private val outputName = "I_mid"
    private val expectedWidth = 1280
    private val expectedHeight = 720

    private fun ensureNetwork(): Boolean {
        network?.let { return true }
        val modelFile = dlcUri?.path?.let { File(it) } ?: return false
        return try {
            val builder = SNPE.NeuralNetwork.Builder(appContext)
                .setModel(modelFile)
                .setRuntimeOrder(
                    NeuralNetwork.Runtime.DSP,
                    NeuralNetwork.Runtime.CPU
                )
                .setPerformanceProfile(NeuralNetwork.PerformanceProfile.HIGH_PERFORMANCE)
                .setInitCacheEnabled(true)
                .setInitCacheDir(appContext.cacheDir)

            val nn = builder.build()
            network = nn
            Log.d("SNPE", "Network built. Runtime order: DSP -> CPU")
            true
        } catch (t: Throwable) {
            Log.w("SNPE", "Failed to build network on DSP; falling back. ${t.message}")
            network = null
            false
        }
    }

    private fun Bitmap.ensureSize(targetW: Int, targetH: Int): Bitmap {
        if (width == targetW && height == targetH) return this
        return Bitmap.createScaledBitmap(this, targetW, targetH, true)
    }

    private fun bitmapToNHWCFloat(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 3)
        var o = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            out[o++] = r / 255f
            out[o++] = g / 255f
            out[o++] = b / 255f
            i++
        }
        return out
    }

    private fun writeFloatToTensor(tensor: ITensor, data: FloatArray) {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data)
        tensor.write(bb)
    }

    private fun readTensorToBitmap(tensor: ITensor, w: Int, h: Int): Bitmap {
        val num = w * h * 3
        val bb = ByteBuffer.allocateDirect(num * 4).order(ByteOrder.nativeOrder())
        tensor.read(bb)
        val fbuf = bb.asFloatBuffer()
        val pixels = IntArray(w * h)
        var pi = 0
        var i = 0
        while (i < w * h) {
            val r = (fbuf.get().coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val g = (fbuf.get().coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val b = (fbuf.get().coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            pixels[pi++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            i++
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    override suspend fun interpolateFrame(previousFrame: Bitmap, nextFrame: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        try {
            if (!ensureNetwork()) return@withContext MockFrameInterpolator().interpolateFrame(previousFrame, nextFrame)
            val nn = network ?: return@withContext MockFrameInterpolator().interpolateFrame(previousFrame, nextFrame)

            val aBmp = previousFrame.ensureSize(expectedWidth, expectedHeight)
            val bBmp = nextFrame.ensureSize(expectedWidth, expectedHeight)

            val aData = bitmapToNHWCFloat(aBmp)
            val bData = bitmapToNHWCFloat(bBmp)

            val in0: ITensor = nn.createTensor(inputName0)
            val in1: ITensor = nn.createTensor(inputName1)
            writeFloatToTensor(in0, aData)
            writeFloatToTensor(in1, bData)

            val out: ITensor = nn.createTensor(outputName)

            val inputs = mapOf(
                inputName0 to in0,
                inputName1 to in1
            )
            val outputs = mutableMapOf(
                outputName to out
            )

            nn.execute(inputs, outputs)
            Log.d("SNPE", "Inference done (attempted DSP, may fallback to CPU)")

            readTensorToBitmap(out, expectedWidth, expectedHeight)
        } catch (t: Throwable) {
            Log.w("SNPE", "Inference failed on SNPE; falling back to Mock. ${t.message}")
            MockFrameInterpolator().interpolateFrame(previousFrame, nextFrame)
        }
    }
}



