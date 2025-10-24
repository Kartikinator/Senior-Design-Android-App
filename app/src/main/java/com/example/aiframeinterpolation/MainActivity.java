package com.example.aiframeinterpolation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 4242;

    private TextView textStatus;
    private Button btnStart;
    private Button btnStop;

    private MediaProjectionManager mpm;

    // Preview surface we own and pass to the service
    private SurfaceView outputSurfaceView;
    private Surface outputSurface;
    private boolean surfaceReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textStatus = findViewById(R.id.txtStatus);
        btnStart   = findViewById(R.id.btnStart);
        btnStop    = findViewById(R.id.btnStop);
        outputSurfaceView = findViewById(R.id.outputSurface);

        // Prepare the Surface we'll give to the foreground service
        outputSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                outputSurface = holder.getSurface();
                surfaceReady = outputSurface != null && outputSurface.isValid();
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                outputSurface = holder.getSurface();
                surfaceReady = outputSurface != null && outputSurface.isValid();
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                outputSurface = null;
            }
        });

        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        btnStart.setOnClickListener(v ->
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE));

        btnStop.setOnClickListener(v -> {
            // Tell the service to stop capturing (and it can call stopSelf())
            Intent stop = new Intent(this, ProjectionFgService.class);
            stop.setAction(ProjectionFgService.ACTION_STOP_CAPTURE);
            startService(stop);
            textStatus.setText("Idle");
        });

        // (Optional) Ask for POST_NOTIFICATIONS on Android 13+ so the FG-service notification is shown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1000);
        }
    }

    @Override
    @SuppressWarnings("deprecation") // using startActivityForResult for simplicity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode == RESULT_OK && data != null) {
            // IMPORTANT: Do NOT call getMediaProjection() here.
            // Hand the permission result + our output Surface to the foreground service instead.
            Intent svc = new Intent(this, ProjectionFgService.class);
            svc.setAction(ProjectionFgService.ACTION_START_CAPTURE);
            svc.putExtra(ProjectionFgService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ProjectionFgService.EXTRA_RESULT_DATA, data);

            // Pass the Surface if it's ready; the service will draw into it
            if (surfaceReady && outputSurface != null && outputSurface.isValid()) {
                svc.putExtra(ProjectionFgService.EXTRA_OUTPUT_SURFACE, outputSurface);
            }

            ContextCompat.startForegroundService(this, svc);
            textStatus.setText(surfaceReady ? "Starting capture…" : "Starting (surface not ready yet)...");
        } else {
            textStatus.setText("Permission denied");
        }
    }
}
