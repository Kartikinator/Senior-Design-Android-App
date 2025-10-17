package com.example.app.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SnpeFrameInterpolator(
    private val appContext: Context,
    private val dlcUri: Uri?
) : FrameInterpolator {
    override suspend fun interpolateFrame(previousFrame: Bitmap, nextFrame: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        MockFrameInterpolator().interpolateFrame(previousFrame, nextFrame)
    }
}



