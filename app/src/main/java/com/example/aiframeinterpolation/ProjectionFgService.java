package com.example.aiframeinterpolation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;

public class ProjectionFgService extends Service {

    // ==== Actions & extras (match MainActivity) ====
    public static final String ACTION_START_CAPTURE =
            "com.example.aiframeinterpolation.START_CAPTURE";
    public static final String ACTION_STOP_CAPTURE  =
            "com.example.aiframeinterpolation.STOP_CAPTURE";
    public static final String EXTRA_RESULT_CODE    = "result_code";
    public static final String EXTRA_RESULT_DATA    = "result_data";
    public static final String EXTRA_OUTPUT_SURFACE = "output_surface";

    // ==== Notification/channel ====
    public static final String CHANNEL_ID = "media_projection";
    public static final int NOTIF_ID = 1001;

    // ==== Capture state ====
    private MediaProjection mediaProjection;
    private MediaProjection.Callback mpCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread capThread;
    private Handler capHandler;

    // ==== Rendering target ====
    private Surface outputSurface;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // ==== FILM ====
    private FilmTFLiteRunner film;
    private Bitmap prevBmp;

    private boolean foregroundStarted = false;

    private static final String TAG = "ProjectionFgService";

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Service can be restarted by system with null intent
        if (intent == null) {
            startInForeground();
            return START_STICKY;
        }

        final String action = intent.getAction();

        if (ACTION_START_CAPTURE.equals(action)) {
            // 1) Enter foreground ASAP
            startInForeground();

            // 2) Receive the Surface we will draw into (from MainActivity)
            outputSurface = intent.getParcelableExtra(EXTRA_OUTPUT_SURFACE);

            // 3) Extract permission result and start projection
            final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            final Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

            startCapture(resultCode, resultData);

        } else if (ACTION_STOP_CAPTURE.equals(action)) {
            stopCapture();
            stopSelf();
        } else {
            startInForeground();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; } // not a bound service

    // ===== Foreground & notification =====

    private void startInForeground() {
        if (foregroundStarted) return;

        PendingIntent pi = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Screen capture in progress")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        foregroundStarted = true;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Media Projection",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ===== Projection lifecycle =====

    private void startCapture(int resultCode, Intent resultData) {
        try {
            // Clean any previous session
            stopCapture();

            // Obtain MediaProjection inside the foreground service (required by Android 14+)
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null (result may be invalid)");
                return;
            }

            // Start a background thread for callbacks first (needed for registering callback)
            capThread = new HandlerThread("mp-cap");
            capThread.start();
            capHandler = new Handler(capThread.getLooper());

            // **Register the callback BEFORE starting capture** (Android 14 requirement)
            mpCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection onStop() — cleaning up");
                    stopCapture();
                    stopSelf();
                }
            };
            mediaProjection.registerCallback(mpCallback, capHandler);

            // ==== Create FILM runner (once per capture session) ====
            film = new FilmTFLiteRunner(
                    getApplicationContext(),
                    "film.tflite",
                    320, 180,   // tune: e.g., 256x144 or 448x252 depending on perf
                    Math.max(2, Runtime.getRuntime().availableProcessors()/2),
                    FilmTFLiteRunner.NormMode.ZERO_TO_ONE,
                    true        // try GPU delegate
            );
            prevBmp = null;

            // Determine capture size
            DisplayMetrics dm = getResources().getDisplayMetrics();
            final int width = dm.widthPixels;
            final int height = dm.heightPixels;
            final int densityDpi = dm.densityDpi;

            // Create ImageReader that matches the display
            imageReader = ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, /*maxImages*/ 3);

            imageReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) return;

                    // Convert the frame to a Bitmap we can draw
                    Bitmap currBmp = imageToBitmap(img);
                    if (currBmp == null) return;

                    // ==== FILM interpolation path ====
                    if (film == null) {
                        // Fallback: raw mirroring
                        drawToSurface(currBmp);
                        currBmp.recycle();
                    } else if (prevBmp == null) {
                        // First frame: display raw and keep as previous
                        drawToSurface(currBmp);
                        prevBmp = currBmp;                // keep as previous (don't recycle)
                    } else {
                        // Interpolate middle frame between prev and current
                        Bitmap mid = film.interpolate(prevBmp, currBmp, 0.5f);
                        drawToSurface(mid);

                        // Advance: free old prev, keep curr as new prev
                        prevBmp.recycle();
                        prevBmp = currBmp;

                        // We drew 'mid' onto the Canvas; safe to recycle
                        mid.recycle();
                    }

                } catch (Throwable t) {
                    Log.e(TAG, "onImageAvailable error", t);
                } finally {
                    if (img != null) img.close();
                }
            }, capHandler);

            // Now it's legal to start the VirtualDisplay
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenMirror",
                    width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    imageReader.getSurface(), null, null
            );

            Log.i(TAG, "Capture started: " + width + "x" + height + "@" + densityDpi);

        } catch (Throwable t) {
            Log.e(TAG, "startCapture failed", t);
            // If something goes wrong, tear down gracefully
            stopCapture();
        }
    }

    private void stopCapture() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (mediaProjection != null) {
                // Unregister callback BEFORE stopping projection
                if (mpCallback != null) {
                    try { mediaProjection.unregisterCallback(mpCallback); } catch (Throwable ignored) {}
                    mpCallback = null;
                }
                mediaProjection.stop();
                mediaProjection = null;
            }
            if (capThread != null) {
                capThread.quitSafely();
                capThread = null;
                capHandler = null;
            }
            // ==== Cleanup FILM + bitmaps ====
            if (prevBmp != null) { try { prevBmp.recycle(); } catch (Throwable ignore) {} prevBmp = null; }
            if (film != null)     { try { film.close(); }   catch (Throwable ignore) {} film = null; }
        } catch (Throwable ignored) { }
    }

    // ===== Drawing helpers =====

    private void drawToSurface(Bitmap bmp) {
        if (outputSurface == null || !outputSurface.isValid()) return;
        Canvas c = null;
        try {
            c = outputSurface.lockCanvas(null);
            c.drawColor(Color.BLACK);
            Rect dst = fitRect(bmp.getWidth(), bmp.getHeight(), c.getWidth(), c.getHeight());
            c.drawBitmap(bmp, null, dst, paint);
        } catch (Throwable t) {
            Log.e(TAG, "drawToSurface error", t);
        } finally {
            if (c != null) outputSurface.unlockCanvasAndPost(c);
        }
    }

    private static Rect fitRect(int sw, int sh, int dw, int dh) {
        float scale = Math.min(dw / (float) sw, dh / (float) sh);
        int w = Math.round(sw * scale), h = Math.round(sh * scale);
        int l = (dw - w) / 2, t = (dh - h) / 2;
        return new Rect(l, t, l + w, t + h);
    }

    private static Bitmap imageToBitmap(Image image) {
        // RGBA_8888 from VirtualDisplay → single plane
        Image.Plane plane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        ByteBuffer buf = plane.getBuffer();

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        // Copy row by row to handle rowStride/pixelStride
        int[] row = new int[width];
        byte[] bytes = new byte[rowStride];
        for (int y = 0; y < height; y++) {
            // Guard remaining() to avoid BufferUnderflow on last rows
            int need = Math.min(rowStride, buf.remaining());
            if (need <= 0) break;
            buf.get(bytes, 0, need);

            int i = 0;
            for (int x = 0; x < width; x++) {
                int r = bytes[i]     & 0xFF;
                int g = bytes[i + 1] & 0xFF;
                int b = bytes[i + 2] & 0xFF;
                int a = bytes[i + 3] & 0xFF;
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                i += pixelStride;
            }
            bmp.setPixels(row, 0, width, 0, y, width, 1);
        }
        return bmp;
    }
}

