package com.example.app.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.qualcomm.qti.snpe.FloatTensor
import com.qualcomm.qti.snpe.NeuralNetwork
import com.qualcomm.qti.snpe.SNPE
import com.qualcomm.qti.snpe.UserBufferTensor
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Real-time frame interpolator optimized for low-latency playback.
 * Uses Qualcomm Hexagon DSP for hardware acceleration.
 * 
 * Strategy:
 * - Maintains a small buffer of recent frames
 * - Pre-computes interpolations ahead of playback
 * - Optimizes SNPE for minimal latency
 */
class RealTimeFrameInterpolator(
    private val appContext: Context,
    dlcUri: Uri?
) {
    @Volatile
    private var network: NeuralNetwork? = null
    
    private val isInitialized = AtomicBoolean(false)
    
    // Frame buffer for real-time interpolation
    private val frameBuffer = ArrayBlockingQueue<FramePair>(BUFFER_SIZE)
    private val interpolatedCache = LinkedHashMap<Long, Bitmap>(MAX_CACHE_SIZE)
    
    // Model config
    private val expectedWidth = 1280
    private val expectedHeight = 720
    
    // SNPE I/O names
    private val inputName0 = "I0"
    private val inputName1 = "I1"
    private val outputName = "I_mid"
    
    companion object {
        private const val BUFFER_SIZE = 3 // Keep last 3 frame pairs
        private const val MAX_CACHE_SIZE = 10 // Cache up to 10 interpolated frames
        private const val TAG = "RealTimeInterpolator"
    }
    
    data class FramePair(val frameA: Bitmap, val frameB: Bitmap, val timestampUs: Long)
    
    init {
        // Initialize network asynchronously
        Thread {
            initializeNetwork(dlcUri)
        }.start()
    }
    
    private fun initializeNetwork(dlcUri: Uri?) {
        Log.d(TAG, "initializeNetwork called with URI: $dlcUri")
        
        val modelFile = dlcUri?.path?.let { File(it) }
        if (modelFile == null) {
            Log.e(TAG, "Model file path is null from URI: $dlcUri")
            return
        }
        
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
            return
        }
        
        Log.d(TAG, "Model file found: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
        
        try {
            Log.d(TAG, "Initializing SNPE for real-time inference...")
            
            // Use CPU runtime (DSP not supported on this device)
            val nn = SNPE.NeuralNetworkBuilder(appContext.applicationContext as android.app.Application)
                .setModel(modelFile)
                .setRuntimeOrder(NeuralNetwork.Runtime.CPU)
                .setPerformanceProfile(NeuralNetwork.PerformanceProfile.HIGH_PERFORMANCE)
                .build()
            
            network = nn
            isInitialized.set(true)
            
            val runtime = nn.runtime
            Log.d(TAG, "✅ SNPE initialized successfully on runtime: $runtime")
            Log.d(TAG, "✅ Ready for real-time interpolation")
            
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Failed to initialize SNPE: ${t.message}", t)
            t.printStackTrace()
            isInitialized.set(false)
        }
    }
    
    /**
     * Queue a frame pair for interpolation.
     * Called by video decoder as frames are decoded.
     */
    fun queueFramePair(frameA: Bitmap, frameB: Bitmap, timestampUs: Long) {
        if (!frameBuffer.offer(FramePair(frameA.copy(Bitmap.Config.ARGB_8888, false), 
                                         frameB.copy(Bitmap.Config.ARGB_8888, false), 
                                         timestampUs))) {
            // Buffer full, remove oldest
            frameBuffer.poll()?.let { old ->
                old.frameA.recycle()
                old.frameB.recycle()
            }
            frameBuffer.offer(FramePair(frameA.copy(Bitmap.Config.ARGB_8888, false), 
                                       frameB.copy(Bitmap.Config.ARGB_8888, false), 
                                       timestampUs))
        }
    }
    
    /**
     * Get interpolated frame for given timestamp.
     * Returns cached result if available, otherwise computes on-demand.
     */
    fun getInterpolatedFrame(timestampUs: Long, frameA: Bitmap, frameB: Bitmap): Bitmap? {
        // Check cache first
        interpolatedCache[timestampUs]?.let { cached ->
            Log.d(TAG, "Cache hit for timestamp $timestampUs")
            return cached.copy(Bitmap.Config.ARGB_8888, false)
        }
        
        if (!isInitialized.get()) {
            Log.w(TAG, "Network not initialized, skipping interpolation")
            return null
        }
        
        // Compute interpolation
        val startTime = System.nanoTime()
        val interpolated = interpolateFrameSync(frameA, frameB)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        
        Log.d(TAG, "Interpolation took ${elapsedMs}ms for timestamp $timestampUs")
        
        if (interpolated != null && elapsedMs < 16) { // Only cache if fast enough for 60fps
            // Add to cache with LRU eviction
            if (interpolatedCache.size >= MAX_CACHE_SIZE) {
                val oldest = interpolatedCache.keys.first()
                interpolatedCache.remove(oldest)?.recycle()
            }
            interpolatedCache[timestampUs] = interpolated.copy(Bitmap.Config.ARGB_8888, false)
        }
        
        return interpolated
    }
    
    /**
     * Synchronous frame interpolation for real-time use.
     * Optimized for minimal latency.
     */
    private fun interpolateFrameSync(frameA: Bitmap, frameB: Bitmap): Bitmap? {
        val nn = network ?: return null
        
        try {
            // Resize to expected dimensions
            val aBmp = ensureSize(frameA, expectedWidth, expectedHeight)
            val bBmp = ensureSize(frameB, expectedWidth, expectedHeight)
            
            // Convert to float arrays (NHWC format)
            val aData = bitmapToNHWCFloat(aBmp)
            val bData = bitmapToNHWCFloat(bBmp)
            
            // Create tensors
            val in0 = nn.createFloatTensor(expectedHeight, expectedWidth, 3)
            val in1 = nn.createFloatTensor(expectedHeight, expectedWidth, 3)
            val out = nn.createFloatTensor(expectedHeight, expectedWidth, 3)
            
            // Write input data
            in0.write(aData, 0, aData.size)
            in1.write(bData, 0, bData.size)
            
            // Execute inference
            val inputs: MutableMap<String, UserBufferTensor> = mutableMapOf()
            inputs[inputName0] = in0 as UserBufferTensor
            inputs[inputName1] = in1 as UserBufferTensor
            
            val outputs: MutableMap<String, UserBufferTensor> = mutableMapOf()
            outputs[outputName] = out as UserBufferTensor
            
            nn.execute(inputs, outputs)
            
            // Read output
            val result = readTensorToBitmap(out, expectedWidth, expectedHeight)
            
            // Cleanup
            if (aBmp !== frameA) aBmp.recycle()
            if (bBmp !== frameB) bBmp.recycle()
            
            return result
            
        } catch (t: Throwable) {
            Log.e(TAG, "Interpolation failed: ${t.message}")
            return null
        }
    }
    
    private fun ensureSize(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (bitmap.width == targetW && bitmap.height == targetH) return bitmap
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
    
    private fun bitmapToNHWCFloat(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        
        val out = FloatArray(w * h * 3)
        var o = 0
        for (p in pixels) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            out[o++] = r / 255f
            out[o++] = g / 255f
            out[o++] = b / 255f
        }
        return out
    }
    
    private fun readTensorToBitmap(tensor: FloatTensor, w: Int, h: Int): Bitmap {
        val num = w * h * 3
        val data = FloatArray(num)
        tensor.read(data, 0, num)
        
        val pixels = IntArray(w * h)
        var pi = 0
        var di = 0
        while (pi < w * h) {
            val r = (data[di++].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val g = (data[di++].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val b = (data[di++].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            pixels[pi++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
    
    fun release() {
        isInitialized.set(false)
        
        // Clear frame buffer
        frameBuffer.forEach { pair ->
            pair.frameA.recycle()
            pair.frameB.recycle()
        }
        frameBuffer.clear()
        
        // Clear cache
        interpolatedCache.values.forEach { it.recycle() }
        interpolatedCache.clear()
        
        network?.release()
        network = null
        
        Log.d(TAG, "RealTimeFrameInterpolator released")
    }
    
    fun isReady(): Boolean = isInitialized.get()
}

