package com.example.app.video

import android.content.Context
import android.net.Uri
import java.io.File
import com.example.app.BuildConfig

fun provideInterpolator(context: Context): FrameInterpolator {
    return if (BuildConfig.FLAVOR == "qidk") {
        val dlcUri = copyDlcFromAssetsIfPresent(context.applicationContext, DEFAULT_DLC_ASSET_NAME)
        SnpeFrameInterpolator(context.applicationContext, dlcUri = dlcUri)
    } else {
        MockFrameInterpolator()
    }
}

// Exposed for real-time player to use the same DLC loading logic
fun provideInterpolatorDlcUri(context: Context): Uri? {
    return copyDlcFromAssetsIfPresent(context.applicationContext, DEFAULT_DLC_ASSET_NAME)
}

private const val DEFAULT_DLC_ASSET_NAME = "rife_3_cached.dlc"

private fun copyDlcFromAssetsIfPresent(appContext: Context, assetName: String): Uri? {
    return try {
        val outFile = File(appContext.cacheDir, assetName)
        if (!outFile.exists()) {
            val am = appContext.assets
            // Check presence by attempting to open; will throw if missing
            am.open(assetName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        Uri.fromFile(outFile)
    } catch (_: Throwable) {
        null
    }
}
