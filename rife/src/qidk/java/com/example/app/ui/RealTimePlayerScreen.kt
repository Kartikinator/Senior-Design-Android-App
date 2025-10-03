package com.example.app.ui

import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.app.video.RealTimeFrameInterpolator
import com.example.app.video.RealTimeInterpolationPlayer
import kotlinx.coroutines.delay
import java.io.File

/**
 * Real-time interpolation player screen.
 * Plays video with on-the-fly RIFE interpolation using SNPE.
 */
@Composable
fun RealTimePlayerScreen(videoUri: Uri?) {
    val context = LocalContext.current
    
    var player by remember { mutableStateOf<RealTimeInterpolationPlayer?>(null) }
    var interpolator by remember { mutableStateOf<RealTimeFrameInterpolator?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Initializing AI chip...") }
    
    // Initialize interpolator
    LaunchedEffect(Unit) {
        statusText = "Loading RIFE model..."
        
        // Copy DLC from assets
        val dlcUri = try {
            val assetName = "rife_3_cached.dlc"
            val outFile = File(context.cacheDir, assetName)
            
            if (!outFile.exists()) {
                context.assets.open(assetName).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            android.util.Log.e("RealTimePlayerScreen", "Failed to copy DLC", e)
            null
        }
        
        statusText = "Initializing SNPE..."
        
        val interp = RealTimeFrameInterpolator(context.applicationContext, dlcUri)
        interpolator = interp
        
        // Wait for initialization
        var attempts = 0
        while (!interp.isReady() && attempts < 50) {
            delay(100)
            attempts++
        }
        
        if (interp.isReady()) {
            statusText = "✅ AI chip ready!"
            isReady = true
        } else {
            statusText = "❌ AI chip failed to initialize"
        }
    }
    
    // Update status when video is selected
    LaunchedEffect(videoUri) {
        if (videoUri != null && isReady) {
            statusText = "✅ Ready to play"
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            interpolator?.release()
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "🚀 Real-Time Interpolation",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (statusText.contains("✅")) MaterialTheme.colorScheme.primary 
                   else if (statusText.contains("❌")) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Video surface
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (videoUri != null) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    android.util.Log.d("RealTimePlayerScreen", "Surface created")
                                    val interp = interpolator
                                    if (interp != null && interp.isReady()) {
                                        try {
                                            val newPlayer = RealTimeInterpolationPlayer(ctx.applicationContext, interp)
                                            newPlayer.prepare(videoUri, holder.surface)
                                            player = newPlayer
                                            statusText = "✅ Video loaded - press Play"
                                            android.util.Log.d("RealTimePlayerScreen", "Player prepared")
                                        } catch (e: Exception) {
                                            statusText = "❌ Failed to load video"
                                            android.util.Log.e("RealTimePlayerScreen", "Failed to prepare player", e)
                                        }
                                    }
                                }
                                
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    player?.release()
                                    player = null
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "👆 Pick a video using the button above",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Playback controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    player?.play()
                    isPlaying = true
                    statusText = "▶️ Playing with real-time interpolation..."
                },
                enabled = isReady && player != null && !isPlaying,
                modifier = Modifier.weight(1f)
            ) {
                Text("▶️ Play")
            }
            
            Button(
                onClick = {
                    if (isPlaying) {
                        player?.pause()
                        statusText = "⏸️ Paused"
                    } else {
                        player?.resume()
                        statusText = "▶️ Playing with real-time interpolation..."
                    }
                    isPlaying = !isPlaying
                },
                enabled = player != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isPlaying) "⏸️ Pause" else "▶️ Resume")
            }
            
            Button(
                onClick = {
                    player?.stop()
                    isPlaying = false
                    statusText = "⏹️ Stopped"
                },
                enabled = player != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("⏹️ Stop")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Info
        Text(
            text = "Interpolating 30fps → 60fps using SNPE on CPU",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
