package com.example.rife

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.qualcomm.qti.snpe.FloatTensor
import com.qualcomm.qti.snpe.NeuralNetwork
import com.qualcomm.qti.snpe.SNPE
import java.io.File
import java.io.FileOutputStream
import java.util.HashMap

class RifeSNPERunner(
    private val context: Context,
    private val modelName: String = "rife_3_cached.dlc",
    private val inputWidth: Int = 1280,
    private val inputHeight: Int = 720
) : AutoCloseable {

    companion object {
        private const val TAG = "RifeSNPERunner"

        init {
            try {
                System.loadLibrary("SNPE")
                Log.i(TAG, "SNPE library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load SNPE library", e)
            }
        }
    }

    private var network: NeuralNetwork? = null
    private val inputTensors = HashMap<String, FloatTensor>()

    init {
        initializeNetwork()
    }

    private fun initializeNetwork() {
        try {
            val modelFile = copyModelToInternalStorage()

            // Cast application context to Application (SNPE expects Application)
            val app = context.applicationContext as Application

            val builder = SNPE.NeuralNetworkBuilder(app)
                .setRuntimeOrder(
                    NeuralNetwork.Runtime.DSP,
                    NeuralNetwork.Runtime.GPU,
                    NeuralNetwork.Runtime.CPU
                )
                .setModel(modelFile)
                .setPerformanceProfile(NeuralNetwork.PerformanceProfile.HIGH_PERFORMANCE)
                .setUseUserSuppliedBuffers(false)
                .setCpuFallbackEnabled(true)
                .setUnsignedPD(false)

            network = builder.build()

            val runtime = network?.getRuntime()
            Log.i(TAG, "SNPE network initialized successfully")
            Log.i(TAG, "Active runtime: $runtime")
            Log.i(TAG, "Model input dimensions: ${inputWidth}x${inputHeight}")

            if (runtime == NeuralNetwork.Runtime.DSP) {
                Log.i(TAG, "✓ Running on NPU/DSP (Hardware Accelerated)")
            } else {
                Log.w(TAG, "⚠ NPU/DSP not available, using fallback: $runtime")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SNPE network", e)
            throw RuntimeException("SNPE initialization failed", e)
        }
    }

    private fun copyModelToInternalStorage(): File {
        val modelFile = File(context.filesDir, modelName)

        if (!modelFile.exists()) {
            try {
                context.assets.open(modelName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Model copied to: ${modelFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model file", e)
                throw RuntimeException("Failed to copy model from assets", e)
            }
        }

        return modelFile
    }

    fun interpolate(frame0: Bitmap, frame1: Bitmap, t: Float = 0.5f): Bitmap {
        val network = this.network ?: throw IllegalStateException("Network not initialized")
        val startTime = System.nanoTime()

        try {
            val input0 = bitmapToFloatArray(frame0)
            val input1 = bitmapToFloatArray(frame1)

            // Use explicit input names (adjust if your DLC uses different names)
            val name0 = "input0"
            val name1 = "input1"

            val inputs = HashMap<String, FloatTensor>()

            // SNPE createFloatTensor expects total element count in this environment
            val numElements = 1 * inputHeight * inputWidth * 3

            inputs[name0] = network.createFloatTensor(numElements)
            inputs[name1] = network.createFloatTensor(numElements)

            inputs[name0]?.write(input0, 0, input0.size)
            inputs[name1]?.write(input1, 0, input1.size)

            val outputs = network.execute(inputs)

            // Take the first tensor from outputs (avoid name-indexing issues)
            val outputTensor = outputs.values.firstOrNull() as? FloatTensor
                ?: throw IllegalStateException("Output tensor not found or wrong type")

            val outputArray = FloatArray(inputWidth * inputHeight * 3)
            outputTensor.read(outputArray, 0, outputArray.size)

            val resultBitmap = floatArrayToBitmap(outputArray, inputWidth, inputHeight)

            val endTime = System.nanoTime()
            val elapsedMs = (endTime - startTime) / 1_000_000.0
            Log.i(TAG, String.format("Interpolation completed in %.2f ms (NPU)", elapsedMs))

            // Release tensors
            inputs.values.forEach { it.release() }
            outputTensor.release()

            return resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Interpolation failed", e)
            throw RuntimeException("SNPE interpolation failed", e)
        }
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val resized = if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        } else {
            bitmap
        }

        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val floatArray = FloatArray(inputWidth * inputHeight * 3)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            val baseIdx = i * 3
            floatArray[baseIdx] = r
            floatArray[baseIdx + 1] = g
            floatArray[baseIdx + 2] = b
        }

        if (resized != bitmap) {
            resized.recycle()
        }

        return floatArray
    }

    private fun floatArrayToBitmap(floatArray: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)

        for (i in pixels.indices) {
            val baseIdx = i * 3
            val r = (floatArray[baseIdx].coerceIn(0f, 1f) * 255).toInt()
            val g = (floatArray[baseIdx + 1].coerceIn(0f, 1f) * 255).toInt()
            val b = (floatArray[baseIdx + 2].coerceIn(0f, 1f) * 255).toInt()

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun isUsingNPU(): Boolean {
        return network?.getRuntime() == NeuralNetwork.Runtime.DSP
    }

    fun getCurrentRuntime(): String {
        return network?.getRuntime()?.toString() ?: "Not initialized"
    }

    override fun close() {
        try {
            inputTensors.values.forEach { it.release() }
            inputTensors.clear()
            network?.release()
            network = null
            Log.i(TAG, "SNPE network released")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing SNPE runner", e)
        }
    }
}
