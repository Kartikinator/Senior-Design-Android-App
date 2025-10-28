package com.example.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.app.video.VideoInterpolationEngine
import com.example.app.video.provideInterpolator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun MainScreen(playSimultaneously: MutableState<Boolean>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val interpolator = remember { provideInterpolator(context) }

    val runtimeText = remember { mutableStateOf("Not initialized") }
    val diagnosticLogs = remember { mutableStateOf("") }

    val topVideoUri = remember { mutableStateOf<Uri?>(null) }
    val bottomVideoUri = remember { mutableStateOf<Uri?>(null) }

    val avgMs = remember { mutableStateOf<Double?>(null) }
    val progress = remember { mutableFloatStateOf(0f) }

    val topPlayer = remember { mutableStateOf<ExoPlayer?>(null) }
    val bottomPlayer = remember { mutableStateOf<ExoPlayer?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {}
            topVideoUri.value = uri
        }
    }

    fun chipLabelFromRuntime(runtime: String): String {
        return when {
            runtime.contains("DSP", ignoreCase = true) -> "NPU / DSP"
            runtime.contains("GPU", ignoreCase = true) -> "GPU"
            runtime.contains("CPU", ignoreCase = true) -> "CPU"
            runtime.contains("Not initialized", ignoreCase = true) -> "Not initialized"
            else -> runtime
        }
    }

    // Helper to safely extract runtime info from the interpolator using reflection
    fun resolveRuntimeFromInterpolator(obj: Any?): String {
        if (obj == null) return "Not initialized"
        return try {
            val cls = obj.javaClass
            // Try getCurrentRuntime()
            try {
                val m = cls.getMethod("getCurrentRuntime")
                m.invoke(obj)?.toString() ?: "Not initialized"
            } catch (_: NoSuchMethodException) {
                // Try isUsingNPU()
                try {
                    val m2 = cls.getMethod("isUsingNPU")
                    val b = m2.invoke(obj) as? Boolean
                    if (b == true) "NPU / DSP" else "CPU/GPU"
                } catch (_: NoSuchMethodException) {
                    "Not initialized"
                }
            }
        } catch (_: Exception) {
            "Not initialized"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Top) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) {
                Text("Pick top video")
            }
            Button(onClick = {
                val uri = topVideoUri.value ?: return@Button
                avgMs.value = null
                bottomVideoUri.value = null
                progress.floatValue = 0f

                // Reuse the interpolator created above when building the engine
                val engine = VideoInterpolationEngine(
                    context = context,
                    interpolator = interpolator
                )

                // Update runtime display right after creating the engine/interpolator (safe)
                runtimeText.value = resolveRuntimeFromInterpolator(interpolator)

                // Get diagnostic logs from interpolator
                try {
                    val method = interpolator.javaClass.getMethod("getDiagnosticLogs")
                    diagnosticLogs.value = method.invoke(interpolator)?.toString() ?: "No logs available"
                } catch (_: Exception) {
                    diagnosticLogs.value = "Could not retrieve diagnostic logs"
                }

                var progressJob: Job? = null
                progressJob = scope.launch(Dispatchers.IO) {
                    engine.progress.collectLatest { p -> progress.floatValue = p }
                }
                scope.launch(Dispatchers.IO) {
                    val result = engine.run(uri)
                    progressJob?.cancel()
                    avgMs.value = result.averageMsPerInterpolatedFrame
                    if (result.outputUri != null) bottomVideoUri.value = result.outputUri
                }
            }) { Text("Interpolate (x2 fps)") }
        }

        if (progress.floatValue > 0f && progress.floatValue < 1f) {
            LinearProgressIndicator(progress = progress.floatValue, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))
        // Display which chip/runtime is currently being used
        Text("Runtime: ${chipLabelFromRuntime(runtimeText.value)}", style = MaterialTheme.typography.bodyMedium)

        // Display diagnostic logs
        if (diagnosticLogs.value.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Text(
                        "Diagnostic Logs:",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        diagnosticLogs.value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Top (original)", style = MaterialTheme.typography.titleMedium)
        VideoPlayer(uriState = topVideoUri, playerState = topPlayer, autoPlay = playSimultaneously.value, modifier = Modifier.weight(1f))

        Spacer(modifier = Modifier.height(12.dp))
        Text("Bottom (interpolated)", style = MaterialTheme.typography.titleMedium)
        VideoPlayer(uriState = bottomVideoUri, playerState = bottomPlayer, autoPlay = playSimultaneously.value, modifier = Modifier.weight(1f))

        Spacer(modifier = Modifier.height(12.dp))
        if (avgMs.value != null) {
            Text("Average interpolate time: ${"%.1f".format(avgMs.value)} ms/frame")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = {
            playSimultaneously.value = false
            val tp = topPlayer.value
            val bp = bottomPlayer.value
            if (tp != null && bp != null) {
                tp.seekTo(0)
                bp.seekTo(0)
                tp.play()
                bp.play()
            }
        }) {
            Text("Play both")
        }
    }
}

@Composable
private fun VideoPlayer(
    uriState: MutableState<Uri?>,
    playerState: MutableState<ExoPlayer?>,
    autoPlay: Boolean,
    modifier: Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(uriState.value) {
        val uri = uriState.value
        // Release any existing player before creating a new one to avoid leaks/warnings.
        playerState.value?.release()
        playerState.value = null

        if (uri != null) {
            val exo = ExoPlayer.Builder(context).build()
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            if (autoPlay) {
                exo.play()
            } else {
                // Render the first frame so the view isn't black when paused
                exo.seekTo(0)
                exo.pause()
            }
            playerState.value = exo
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerState.value?.release()
            playerState.value = null
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = playerState.value
                useController = true
            }
        },
        update = { view ->
            view.player = playerState.value
        },
        modifier = modifier.fillMaxSize()
    )
}
