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
import com.qualcomm.qti.snpe.FloatTensor
import com.qualcomm.qti.snpe.UserBufferTensor

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

    init {
        logDeviceInfo()
    }

    private fun logDeviceInfo() {
        try {
            Log.i("SNPE", "=== Device Information ===")
            Log.i("SNPE", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            Log.i("SNPE", "Board: ${android.os.Build.BOARD}")
            Log.i("SNPE", "Hardware: ${android.os.Build.HARDWARE}")
            Log.i("SNPE", "Android version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            Log.i("SNPE", "SoC: ${android.os.Build.SOC_MODEL}")
            Log.i("SNPE", "===========================")
        } catch (t: Throwable) {
            Log.w("SNPE", "Could not log device info: ${t.message}")
        }
    }

    private fun ensureNetwork(): Boolean {
        network?.let { return true }
        val modelFile = dlcUri?.path?.let { File(it) } ?: run {
            Log.e("SNPE", "Model file URI is null or invalid")
            return false
        }

        if (!modelFile.exists()) {
            Log.e("SNPE", "Model file does not exist: ${modelFile.absolutePath}")
            return false
        }

        Log.i("SNPE", "=== SNPE Initialization ===")
        Log.i("SNPE", "Model file: ${modelFile.absolutePath}")
        Log.i("SNPE", "Model size: ${modelFile.length()} bytes")

        return try {
            // Check runtime availability
            Log.i("SNPE", "Checking runtime availability...")
            val dspAvailable = SNPE.isRuntimeAvailable(NeuralNetwork.Runtime.DSP)
            val gpuAvailable = SNPE.isRuntimeAvailable(NeuralNetwork.Runtime.GPU)
            val cpuAvailable = SNPE.isRuntimeAvailable(NeuralNetwork.Runtime.CPU)

            Log.i("SNPE", "DSP/NPU available: $dspAvailable")
            Log.i("SNPE", "GPU available: $gpuAvailable")
            Log.i("SNPE", "CPU available: $cpuAvailable")

            if (!dspAvailable) {
                Log.w("SNPE", "⚠ DSP/NPU runtime NOT available on this device!")
                Log.w("SNPE", "Possible reasons:")
                Log.w("SNPE", "  1. Device doesn't have Hexagon DSP")
                Log.w("SNPE", "  2. Missing Hexagon drivers/firmware")
                Log.w("SNPE", "  3. Android version incompatibility")
                Log.w("SNPE", "  4. Model not quantized for DSP")
            }

            Log.i("SNPE", "Building network with runtime order: DSP -> GPU -> CPU")
            val nn = SNPE.NeuralNetworkBuilder(appContext.applicationContext as android.app.Application)
                .setModel(modelFile)
                .setRuntimeOrder(
                    NeuralNetwork.Runtime.DSP,
                    NeuralNetwork.Runtime.GPU,
                    NeuralNetwork.Runtime.CPU
                )
                .setPerformanceProfile(NeuralNetwork.PerformanceProfile.HIGH_PERFORMANCE)
                .setUseUserSuppliedBuffers(false)
                .setCpuFallbackEnabled(true)
                .setUnsignedPD(false)
                .build()

            network = nn
            val runtime = nn.runtime
            Log.i("SNPE", "=== Network Built Successfully ===")
            Log.i("SNPE", "Active runtime: $runtime")

            when (runtime) {
                NeuralNetwork.Runtime.DSP -> {
                    Log.i("SNPE", "✓✓✓ SUCCESS: Running on NPU/DSP (Hardware Accelerated) ✓✓✓")
                }
                NeuralNetwork.Runtime.GPU -> {
                    Log.w("SNPE", "⚠ Running on GPU (not NPU)")
                    Log.w("SNPE", "DSP may not support this model's operations")
                }
                NeuralNetwork.Runtime.CPU -> {
                    Log.e("SNPE", "⚠⚠⚠ WARNING: Fell back to CPU ⚠⚠⚠")
                    Log.e("SNPE", "This means:")
                    Log.e("SNPE", "  • NPU/DSP is not available OR")
                    Log.e("SNPE", "  • Model has unsupported operations for DSP")
                    Log.e("SNPE", "  • Model may not be properly quantized for DSP")
                }
                else -> {
                    Log.w("SNPE", "Unknown runtime: $runtime")
                }
            }
            true
        } catch (t: Throwable) {
            Log.e("SNPE", "=== Network Build FAILED ===")
            Log.e("SNPE", "Error: ${t.message}")
            Log.e("SNPE", "Stack trace: ${t.stackTraceToString()}")
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

    private fun writeFloatToTensor(tensor: FloatTensor, data: FloatArray) {
        tensor.write(data, 0, data.size)
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

    override suspend fun interpolateFrame(previousFrame: Bitmap, nextFrame: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        try {
            if (!ensureNetwork()) return@withContext MockFrameInterpolator().interpolateFrame(previousFrame, nextFrame)
            val nn = network ?: return@withContext MockFrameInterpolator().interpolateFrame(previousFrame, nextFrame)

            val aBmp = previousFrame.ensureSize(expectedWidth, expectedHeight)
            val bBmp = nextFrame.ensureSize(expectedWidth, expectedHeight)

            val aData = bitmapToNHWCFloat(aBmp)
            val bData = bitmapToNHWCFloat(bBmp)

            val in0 = nn.createFloatTensor(expectedHeight, expectedWidth, 3)
            val in1 = nn.createFloatTensor(expectedHeight, expectedWidth, 3)
            writeFloatToTensor(in0, aData)
            writeFloatToTensor(in1, bData)

            val out = nn.createFloatTensor(expectedHeight, expectedWidth, 3)

            val inputs: MutableMap<String, UserBufferTensor> = mutableMapOf()
            inputs[inputName0] = in0 as UserBufferTensor
            inputs[inputName1] = in1 as UserBufferTensor

            val outputs: MutableMap<String, UserBufferTensor> = mutableMapOf()
            outputs[outputName] = out as UserBufferTensor

            nn.execute(inputs, outputs)
            Log.d("SNPE", "Inference done (attempted DSP, may fallback to CPU)")

            readTensorToBitmap(out, expectedWidth, expectedHeight)
        } catch (t: Throwable) {
            Log.w("SNPE", "Inference failed on SNPE; falling back to Mock. ${t.message}")
            MockFrameInterpolator().interpolateFrame(previousFrame, nextFrame)
        }
    }

    fun getCurrentRuntime(): String {
        val runtime = network?.runtime
        return when (runtime) {
            NeuralNetwork.Runtime.DSP -> "NPU (DSP/HTP)"
            NeuralNetwork.Runtime.GPU -> "GPU"
            NeuralNetwork.Runtime.CPU -> "CPU"
            NeuralNetwork.Runtime.GPU_FLOAT16 -> "GPU (FP16)"
            else -> "Not initialized"
        }
    }

    fun isUsingNPU(): Boolean {
        return network?.runtime == NeuralNetwork.Runtime.DSP
    }
}



