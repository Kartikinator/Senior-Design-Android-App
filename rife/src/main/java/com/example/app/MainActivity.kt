package com.example.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.app.ui.theme.AppTheme
import com.example.app.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                RifeApp()
            }
        }
    }
}

@Composable
fun RifeApp() {
    var selectedMode by remember { mutableStateOf(InterpolationMode.BATCH) }
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedVideo = uri
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "RIFE Frame Interpolation",
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Mode selection tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == InterpolationMode.BATCH,
                        onClick = { selectedMode = InterpolationMode.BATCH },
                        label = { Text("Batch Mode") },
                        modifier = Modifier.weight(1f)
                    )
                    
                    if (BuildConfig.FLAVOR == "qidk") {
                        FilterChip(
                            selected = selectedMode == InterpolationMode.REALTIME,
                            onClick = { selectedMode = InterpolationMode.REALTIME },
                            label = { Text("Real-Time 🚀") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Video picker button
                Button(
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedVideo == null) "Pick Video" else "Change Video")
                }
                
                // Mode description
                Text(
                    text = when (selectedMode) {
                        InterpolationMode.BATCH -> "Process entire video, then save to file"
                        InterpolationMode.REALTIME -> "Play with on-the-fly interpolation (Hexagon DSP)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedMode) {
                InterpolationMode.BATCH -> {
                    val playSimultaneously = remember { mutableStateOf(false) }
                    MainScreen(playSimultaneously = playSimultaneously)
                }
                InterpolationMode.REALTIME -> {
                    // Real-time mode
                    RealTimeMode(selectedVideo)
                }
            }
        }
    }
}

// Real-time mode implementation
@Composable
fun RealTimeMode(videoUri: Uri?) {
    if (BuildConfig.FLAVOR == "qidk") {
        // Use real-time player with SNPE (only available in qidk flavor)
        com.example.app.ui.RealTimePlayerScreen(videoUri)
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Real-time mode requires qidk build variant")
        }
    }
}

enum class InterpolationMode {
    BATCH,
    REALTIME
}
