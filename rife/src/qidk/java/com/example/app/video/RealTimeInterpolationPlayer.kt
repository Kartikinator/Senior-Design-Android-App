package com.example.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File
import android.media.Image

/**
 * Real-time video player with RIFE frame interpolation.
 * 
 * Architecture:
 * 1. MediaCodec decodes video frames to YUV
 * 2. Convert YUV to Bitmap
 * 3. Interpolate between consecutive frames using SNPE
 * 4. Render both original and interpolated frames to Surface
 * 
 * Target: 60fps playback from 30fps source
 */
class RealTimeInterpolationPlayer(
    private val context: Context,
    private val interpolator: RealTimeFrameInterpolator
) {
    
    private var decoder: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private var renderSurface: Surface? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    
    @Volatile
    private var isPlaying = false
    
    @Volatile
    private var isPaused = false
    
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoDurationUs = 0L
    private var videoFrameRate = 30f
    
    // Frame buffering
    private var lastFrame: Bitmap? = null
    
    companion object {
        private const val TAG = "RealTimePlayer"
        private const val TIMEOUT_US = 10000L
    }
    
    /**
     * Prepare video for playback
     */
    fun prepare(videoUri: Uri, targetSurface: Surface) {
        release()
        
        renderSurface = targetSurface
        
        try {
            // Get video path
            val videoPath = getVideoPath(videoUri)
            
            // Setup extractor
            extractor = MediaExtractor().apply {
                setDataSource(videoPath)
            }
            
            // Find video track
            val trackIndex = findVideoTrack() ?: throw IllegalArgumentException("No video track found")
            extractor?.selectTrack(trackIndex)
            
            val format = extractor?.getTrackFormat(trackIndex) ?: throw IllegalArgumentException("No format")
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
            
            // Try to get frame rate, default to 30 if not available
            videoFrameRate = try {
                format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
            } catch (e: Exception) {
                30f
            }
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            
            // Setup decoder - use software decoder for frame access
            decoder = MediaCodec.createDecoderByType(mime).apply {
                // Don't pass surface, we need bitmap access
                configure(format, null, null, 0)
                start()
            }
            
            Log.d(TAG, "✅ Prepared: ${videoWidth}x${videoHeight} @ ${videoFrameRate}fps")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to prepare video", e)
            release()
            throw e
        }
    }
    
    private fun getVideoPath(videoUri: Uri): String {
        return videoUri.path ?: run {
            // Copy from content URI to temp file
            val input = context.contentResolver.openInputStream(videoUri)
                ?: throw IllegalArgumentException("Cannot open video URI")
            val temp = File(context.cacheDir, "temp_playback_${System.currentTimeMillis()}.mp4")
            temp.outputStream().use { out ->
                input.copyTo(out)
            }
            input.close()
            temp.absolutePath
        }
    }
    
    private fun findVideoTrack(): Int? {
        val trackCount = extractor?.trackCount ?: 0
        return (0 until trackCount).firstOrNull { i ->
            extractor?.getTrackFormat(i)?.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
    }
    
    /**
     * Start playback with real-time interpolation
     */
    fun play() {
        if (isPlaying) return
        if (decoder == null || extractor == null) {
            Log.e(TAG, "Player not prepared")
            return
        }
        
        isPlaying = true
        isPaused = false
        
        playbackJob = scope.launch {
            try {
                runPlaybackLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
            }
        }
        
        Log.d(TAG, "▶️ Playback started")
    }
    
    /**
     * Main playback loop with interpolation
     */
    private suspend fun runPlaybackLoop() = withContext(Dispatchers.Default) {
        val dec = decoder ?: return@withContext
        val ext = extractor ?: return@withContext
        val surface = renderSurface ?: return@withContext
        
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false
        
        // Timing
        val frameDurationMs = (1000f / videoFrameRate).toLong()
        val interpolatedFrameDurationMs = frameDurationMs / 2
        
        var frameCount = 0
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "🎬 Starting playback: ${videoFrameRate}fps → ${videoFrameRate * 2}fps")
        Log.d(TAG, "Frame timing: original=${frameDurationMs}ms, interpolated=${interpolatedFrameDurationMs}ms")
        
        while (isActive && !outputEOS && isPlaying) {
            if (isPaused) {
                delay(10)
                continue
            }
            
            // Feed input buffers
            if (!inputEOS) {
                val inputIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = dec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = ext.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEOS = true
                        } else {
                            val sampleTime = ext.sampleTime
                            dec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
            }
            
            // Process output buffers
            val outputIndex = dec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            
            when {
                outputIndex >= 0 -> {
                    // Get decoded frame as Image
                    val image = dec.getOutputImage(outputIndex)
                    
                    if (image != null) {
                        // Convert to Bitmap
                        val currentFrame = imageToBitmap(image)
                        image.close()
                        
                        if (currentFrame != null) {
                            // Render original frame
                            renderFrameToSurface(surface, currentFrame)
                            delay(interpolatedFrameDurationMs)
                            frameCount++
                            
                            // Interpolate if we have a previous frame
                            if (lastFrame != null && interpolator.isReady()) {
                                val interpFrame = interpolator.getInterpolatedFrame(
                                    bufferInfo.presentationTimeUs,
                                    lastFrame!!,
                                    currentFrame
                                )
                                
                                if (interpFrame != null) {
                                    renderFrameToSurface(surface, interpFrame)
                                    delay(interpolatedFrameDurationMs)
                                    frameCount++
                                    interpFrame.recycle()
                                }
                            } else {
                                // No interpolation yet, just delay
                                delay(interpolatedFrameDurationMs)
                            }
                            
                            // Update last frame
                            lastFrame?.recycle()
                            lastFrame = currentFrame
                        }
                    }
                    
                    dec.releaseOutputBuffer(outputIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                        val actualFps = frameCount / elapsed
                        Log.d(TAG, "🏁 Playback ended: $frameCount frames in ${elapsed}s (${actualFps}fps)")
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Format changed: ${dec.outputFormat}")
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No frame available
                    delay(5)
                }
            }
        }
        
        isPlaying = false
    }
    
    /**
     * Convert MediaCodec Image (YUV) to Bitmap (ARGB)
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            
            // Get YUV planes
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            
            // Create bitmap
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            // Simple YUV to RGB conversion
            // (This is a simplified version - production would use a more efficient method)
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            
            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            
            val pixels = IntArray(width * height)
            
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val yIndex = row * yRowStride + col
                    val uvRow = row / 2
                    val uvCol = col / 2
                    val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride
                    
                    val y = yBuffer.get(yIndex).toInt() and 0xff
                    val u = (uBuffer.get(uvIndex).toInt() and 0xff) - 128
                    val v = (vBuffer.get(uvIndex).toInt() and 0xff) - 128
                    
                    // YUV to RGB conversion
                    val r = (y + 1.370705f * v).toInt().coerceIn(0, 255)
                    val g = (y - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
                    val b = (y + 1.732446f * u).toInt().coerceIn(0, 255)
                    
                    pixels[row * width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }
    
    /**
     * Render bitmap to surface using Canvas
     */
    private fun renderFrameToSurface(surface: Surface, bitmap: Bitmap) {
        try {
            val canvas = surface.lockCanvas(null)
            if (canvas != null) {
                // Scale bitmap to fill canvas
                val scaleX = canvas.width.toFloat() / bitmap.width
                val scaleY = canvas.height.toFloat() / bitmap.height
                val scale = maxOf(scaleX, scaleY)
                
                canvas.save()
                canvas.scale(scale, scale)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                canvas.restore()
                
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render frame", e)
        }
    }
    
    fun pause() {
        isPaused = true
        Log.d(TAG, "⏸️ Paused")
    }
    
    fun resume() {
        isPaused = false
        Log.d(TAG, "▶️ Resumed")
    }
    
    fun stop() {
        isPlaying = false
        isPaused = false
        playbackJob?.cancel()
        playbackJob = null
        Log.d(TAG, "⏹️ Stopped")
    }
    
    fun release() {
        stop()
        
        decoder?.stop()
        decoder?.release()
        decoder = null
        
        extractor?.release()
        extractor = null
        
        lastFrame?.recycle()
        lastFrame = null
        
        scope.cancel()
        
        Log.d(TAG, "Released")
    }
    
    fun isInterpolatorReady(): Boolean = interpolator.isReady()
}
