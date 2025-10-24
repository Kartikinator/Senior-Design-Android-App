package com.example.aiframeinterpolation;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * FILM (TFLite) helper: (Bitmap a, Bitmap b, t) -> interpolated Bitmap.
 * - Robustly maps inputs to (time, x0, x1) by inspecting tensor names/shapes.
 * - Resizes dynamic inputs to [1,inH,inW,3].
 * - Finds the correct RGB output tensor and allocates buffer accordingly.
 */
public class FilmTFLiteRunner implements AutoCloseable {
    private static final String TAG = "FilmTFLiteRunner";

    /** Normalization mode applied to inputs/outputs. */
    public enum NormMode { ZERO_TO_ONE, NEG_ONE_TO_ONE }

    private final Interpreter tfl;
    private final int inW, inH;          // working input size you chose
    private final ByteBuffer in0Buf;     // [1, inH, inW, 3] float32
    private final ByteBuffer in1Buf;     // [1, inH, inW, 3] float32
    private ByteBuffer outBuf;           // size is chosen from selected output tensor
    private final float[] tScalar = new float[1];

    private final NormMode normMode;
    private final boolean useGpu;

    // Reusable scratch bitmaps to avoid per-frame allocations
    private final Bitmap scratchA;
    private final Bitmap scratchB;

    private GpuDelegate gpuDelegate; // closed in close()

    // Robust input mapping (indices discovered once)
    private int idxTime = -1, idxA = -1, idxB = -1;
    private int inputCount = -1;

    // Chosen output info
    private int outIdx = 0;
    private int outW = 0, outH = 0;

    /**
     * @param ctx        application context
     * @param assetName  "film.tflite" (place in app/src/main/assets/)
     * @param modelW     model input width (e.g., 320)
     * @param modelH     model input height (e.g., 180)
     * @param numThreads interpreter threads if not using GPU
     * @param norm       normalization mode (ZERO_TO_ONE or NEG_ONE_TO_ONE)
     * @param tryGpu     true to use GPU delegate if supported
     */
    public FilmTFLiteRunner(Context ctx,
                            String assetName,
                            int modelW,
                            int modelH,
                            int numThreads,
                            NormMode norm,
                            boolean tryGpu) {
        this.inW = modelW;
        this.inH = modelH;
        this.normMode = (norm != null) ? norm : NormMode.ZERO_TO_ONE;

        ByteBuffer model = loadModelBuffer(ctx, assetName);

        Interpreter.Options opts = new Interpreter.Options();
        boolean gpuAttached = false;
        if (tryGpu) {
            try {
                CompatibilityList compat = new CompatibilityList();
                if (compat.isDelegateSupportedOnThisDevice()) {
                    gpuDelegate = new GpuDelegate(compat.getBestOptionsForThisDevice());
                    opts.addDelegate(gpuDelegate);
                    gpuAttached = true;
                    Log.i(TAG, "Using TFLite GPU delegate");
                } else {
                    Log.i(TAG, "GPU delegate not supported on this device; falling back to CPU");
                }
            } catch (Throwable t) {
                Log.w(TAG, "GPU delegate init failed: " + t.getMessage());
            }
        }
        this.useGpu = gpuAttached;

        if (!gpuAttached) {
            opts.setNumThreads(Math.max(1, numThreads));
            opts.setUseXNNPACK(true);
            Log.i(TAG, "Using CPU (threads=" + Math.max(1, numThreads) + ", XNNPACK)");
        }

        this.tfl = new Interpreter(model, opts);

        // ===== Discover input mapping by name/shape =====
        inputCount = tfl.getInputTensorCount();
        int oc = tfl.getOutputTensorCount();
        if (inputCount < 2 || oc < 1) {
            throw new IllegalStateException("Unexpected FILM IO: inputs=" + inputCount + " outputs=" + oc);
        }

        for (int i = 0; i < inputCount; i++) {
            Tensor t = tfl.getInputTensor(i);
            String name = t.name();          // e.g., "serving_default_time:0"
            int[] shape = t.shape();         // time often [] or [1]; images often [1,H,W,3]

            // Prefer explicit "time" name
            if (name != null && name.toLowerCase().contains("time")) {
                idxTime = i;
                continue;
            }
            // Scalar-ish goes to time if we don't have one yet
            if (shape.length <= 1 && idxTime < 0) {
                idxTime = i;
                continue;
            }
            // Prefer 4D for images (A then B)
            if (shape.length == 4) {
                if (idxA < 0) idxA = i;
                else          idxB = i;
                continue;
            }
            // Fallback
            if (idxA < 0) idxA = i;
            else          idxB = i;
        }

        boolean ok =
                (inputCount == 3 && idxTime >= 0 && idxA >= 0 && idxB >= 0) ||
                        (inputCount == 2 && idxA >= 0 && idxB >= 0 && idxTime < 0);
        if (!ok) {
            StringBuilder sb = new StringBuilder("Inputs:\n");
            for (int i = 0; i < inputCount; i++) {
                Tensor ti = tfl.getInputTensor(i);
                sb.append("  ").append(i).append(" name=").append(ti.name())
                        .append(" shape=").append(Arrays.toString(ti.shape())).append('\n');
            }
            throw new IllegalStateException(
                    "Could not infer FILM input indices. time=" + idxTime +
                            " A=" + idxA + " B=" + idxB + "\n" + sb
            );
        }
        Log.i(TAG, "Input mapping -> time:" + idxTime + " A:" + idxA + " B:" + idxB +
                " (inputCount=" + inputCount + ")");

        // ===== Resize dynamic inputs to your working size, then allocate tensors =====
        try {
            if (idxA >= 0) tfl.resizeInput(idxA, new int[]{1, inH, inW, 3});
            if (idxB >= 0) tfl.resizeInput(idxB, new int[]{1, inH, inW, 3});
            if (idxTime >= 0 && inputCount == 3) {
                int[] sigT = tfl.getInputTensor(idxTime).shapeSignature();
                if (sigT.length == 0 || (sigT.length == 1 && (sigT[0] == -1 || sigT[0] == 1))) {
                    tfl.resizeInput(idxTime, new int[]{1}); // normalize scalar time
                }
            }
            tfl.allocateTensors();

            for (int i = 0; i < inputCount; i++) {
                Tensor ti = tfl.getInputTensor(i);
                Log.i(TAG, "IN " + i + " after-resize shape=" + Arrays.toString(ti.shape()));
            }
        } catch (Throwable e) {
            Log.w(TAG, "resizeInput/allocateTensors failed: " + e.getMessage());
        }

        // ===== Discover frame output
        // Prefer the largest 4D NHWC RGB tensor (C=3), not tiny summary tensors like [1,1,1,3].
        int bestRgbIdx = -1;
        int bestRgbPixels = -1;
        int fallbackIdx = 0;
        int fallbackBytes = -1;
        for (int i = 0; i < oc; i++) {
            Tensor ot = tfl.getOutputTensor(i);
            int[] s = ot.shape();
            int nb = ot.numBytes();
            if (nb > fallbackBytes) { fallbackBytes = nb; fallbackIdx = i; }

            if (s != null && s.length == 4 && s[0] == 1 && s[3] == 3) {
                int pixels = Math.max(1, s[1]) * Math.max(1, s[2]);
                if (pixels > bestRgbPixels) {
                    bestRgbPixels = pixels;
                    bestRgbIdx = i;
                }
            }
        }
        if (bestRgbIdx >= 0) {
            Tensor ot = tfl.getOutputTensor(bestRgbIdx);
            int[] s = ot.shape();
            outIdx = bestRgbIdx;
            outH = s[1];
            outW = s[2];
        } else {
            // Fallback to the largest tensor by byte size if no RGB tensor found
            Tensor ot = tfl.getOutputTensor(fallbackIdx);
            int[] s = ot.shape();
            outIdx = fallbackIdx;
            if (s.length == 4) { outH = s[1]; outW = s[2]; }
            else if (s.length == 3) { outH = s[0]; outW = s[1]; }
            else { outH = inH; outW = inW; }
        }

        // Allocate output buffer to EXACT size of chosen tensor
        Tensor outT = tfl.getOutputTensor(outIdx);
        outBuf = ByteBuffer.allocateDirect(outT.numBytes()).order(ByteOrder.nativeOrder());

        // Log outputs for visibility
        for (int i = 0; i < oc; i++) {
            Tensor ot = tfl.getOutputTensor(i);
            Log.i(TAG, "OUT " + i + " shape=" + Arrays.toString(ot.shape())
                    + " bytes=" + ot.numBytes() + (i == outIdx ? "  <-- selected" : ""));
        }

        // Pre-allocate input buffers
        int elem = 1 * inH * inW * 3; // NHWC
        this.in0Buf = ByteBuffer.allocateDirect(elem * 4).order(ByteOrder.nativeOrder());
        this.in1Buf = ByteBuffer.allocateDirect(elem * 4).order(ByteOrder.nativeOrder());

        // Reusable scratch bitmaps at model size
        this.scratchA = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888, false,
                ColorSpace.get(ColorSpace.Named.SRGB));
        this.scratchB = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888, false,
                ColorSpace.get(ColorSpace.Named.SRGB));
    }

    /** Convenience ctor with sensible defaults (GPU if possible, ZERO_TO_ONE norm). */
    public static FilmTFLiteRunner createDefault(Context ctx, String assetName, int modelW, int modelH) {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        return new FilmTFLiteRunner(ctx, assetName, modelW, modelH, threads, NormMode.ZERO_TO_ONE, true);
    }

    /** Main entry: interpolate two frames at time t (0..1). Returns ARGB_8888 Bitmap of size outW x outH. */
    public Bitmap interpolate(Bitmap bmp0, Bitmap bmp1, float t) {
        // 1) Center-crop + resize into reusable scratch bitmaps (preserve aspect)
        centerCropResizeInto(bmp0, scratchA, inW, inH);
        centerCropResizeInto(bmp1, scratchB, inW, inH);

        // 2) Preprocess into NHWC float32 according to norm
        fillNHWC(scratchA, in0Buf, normMode);
        fillNHWC(scratchB, in1Buf, normMode);
        tScalar[0] = clamp01(t);

        // 3) Run TFLite
        long t0 = System.nanoTime();
        Bitmap out = runOnce(in0Buf, in1Buf, tScalar, outBuf);
        long t1 = System.nanoTime();
        Log.i(TAG, String.format("FILM TFLite (gpu=%s) ran in %.2f ms",
                useGpu ? "yes" : "no", (t1 - t0) / 1e6));

        return out;
    }

    /** Convenience: interpolate at t=0.5 (mid-frame). */
    public Bitmap interpolateMid(Bitmap bmp0, Bitmap bmp1) {
        return interpolate(bmp0, bmp1, 0.5f);
    }

    @Override
    public void close() {
        try { tfl.close(); } catch (Throwable ignored) {}
        try { if (gpuDelegate != null) gpuDelegate.close(); } catch (Throwable ignored) {}
        try { scratchA.recycle(); } catch (Throwable ignored) {}
        try { scratchB.recycle(); } catch (Throwable ignored) {}
    }

    // ====== Core run & helpers ======

    private Bitmap runOnce(ByteBuffer inA, ByteBuffer inB, float[] tVal, ByteBuffer out) {
        out.rewind();

        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(outIdx, out); // use the discovered output index

        if (idxTime >= 0 && inputCount == 3) {
            Object[] inputs = new Object[inputCount];
            inputs[idxTime] = tVal;
            inputs[idxA]    = inA;
            inputs[idxB]    = inB;
            tfl.runForMultipleInputsOutputs(inputs, outputs);
        } else if (inputCount == 2) {
            Object[] inputs = new Object[2];
            inputs[0] = (idxA < idxB) ? inA : inB;
            inputs[1] = (idxA < idxB) ? inB : inA;
            tfl.runForMultipleInputsOutputs(inputs, outputs);
        } else {
            throw new IllegalStateException("Unsupported input configuration: inputCount=" + inputCount +
                    " idxTime=" + idxTime + " idxA=" + idxA + " idxB=" + idxB);
        }

        return toBitmap(out, outW, outH, normMode);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Convert ARGB_8888 Bitmap -> NHWC float32 according to normalization (rewinds dst). */
    private static void fillNHWC(Bitmap src, ByteBuffer dst, NormMode mode) {
        dst.rewind();
        final int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        if (mode == NormMode.ZERO_TO_ONE) {
            for (int p : pixels) {
                dst.putFloat(((p >> 16) & 0xFF) / 255f); // R
                dst.putFloat(((p >>  8) & 0xFF) / 255f); // G
                dst.putFloat(( p        & 0xFF) / 255f); // B
            }
        } else { // NEG_ONE_TO_ONE
            for (int p : pixels) {
                dst.putFloat(((p >> 16) & 0xFF) / 127.5f - 1f);
                dst.putFloat(((p >>  8) & 0xFF) / 127.5f - 1f);
                dst.putFloat(( p        & 0xFF) / 127.5f - 1f);
            }
        }
        dst.rewind();
    }

    /** NHWC float32 -> ARGB_8888 Bitmap (respects the chosen normalization for clamping). */
    private static Bitmap toBitmap(ByteBuffer src, int w, int h, NormMode mode) {
        src.rewind();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, /*hasAlpha*/ false,
                ColorSpace.get(ColorSpace.Named.SRGB));
        int[] pixels = new int[w * h];

        if (mode == NormMode.ZERO_TO_ONE) {
            for (int i = 0; i < pixels.length; i++) {
                int r = (int)(clamp01(src.getFloat()) * 255f + 0.5f);
                int g = (int)(clamp01(src.getFloat()) * 255f + 0.5f);
                int b = (int)(clamp01(src.getFloat()) * 255f + 0.5f);
                pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        } else { // NEG_ONE_TO_ONE
            for (int i = 0; i < pixels.length; i++) {
                float fr = src.getFloat(), fg = src.getFloat(), fb = src.getFloat();
                int r = (int)(Math.max(-1f, Math.min(1f, fr)) * 127.5f + 127.5f + 0.5f);
                int g = (int)(Math.max(-1f, Math.min(1f, fg)) * 127.5f + 127.5f + 0.5f);
                int b = (int)(Math.max(-1f, Math.min(1f, fb)) * 127.5f + 127.5f + 0.5f);
                pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    /** Center-crop the source to target aspect, then scale into dst-sized bitmap. */
    private static void centerCropResizeInto(Bitmap src, Bitmap dst, int dstW, int dstH) {
        final int sw = src.getWidth();
        final int sh = src.getHeight();
        final float srcAspect = (float) sw / (float) sh;
        final float dstAspect = (float) dstW / (float) dstH;

        int cx, cy, cw, ch;
        if (srcAspect > dstAspect) {
            ch = sh;
            cw = Math.round(ch * dstAspect);
            cx = (sw - cw) / 2;
            cy = 0;
        } else {
            cw = sw;
            ch = Math.round(cw / dstAspect);
            cx = 0;
            cy = (sh - ch) / 2;
        }

        Bitmap cropped = Bitmap.createBitmap(
                src, clamp(cx, 0, sw - 1), clamp(cy, 0, sh - 1),
                clamp(cw, 1, sw), clamp(ch, 1, sh));
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, dstW, dstH, true);

        dst.eraseColor(0xFF000000);
        int[] pix = new int[dstW * dstH];
        scaled.getPixels(pix, 0, dstW, 0, 0, dstW, dstH);
        dst.setPixels(pix, 0, dstW, 0, 0, dstW, dstH);

        cropped.recycle();
        scaled.recycle();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Load tflite as a memory-mapped buffer when possible; fall back to direct buffer. */
    private static ByteBuffer loadModelBuffer(Context ctx, String assetName) {
        // Try mmap (requires noCompress "tflite" but will still work if compressed; mmap just faster)
        try {
            AssetFileDescriptor afd = ctx.getAssets().openFd(assetName);
            FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
            FileChannel fc = fis.getChannel();
            MappedByteBuffer mbb = fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
            fis.close();
            afd.close();
            return mbb;
        } catch (Throwable mmapFail) {
            Log.w(TAG, "mmap failed, falling back to copy: " + mmapFail.getMessage());
        }
        // Fallback: copy to a direct ByteBuffer
        try (var is = ctx.getAssets().open(assetName);
             var bos = new ByteArrayOutputStream(1 << 20)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            ByteBuffer bb = ByteBuffer.allocateDirect(bos.size()).order(ByteOrder.nativeOrder());
            bb.put(bos.toByteArray());
            bb.rewind();
            return bb;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load asset: " + assetName, e);
        }
    }
}

