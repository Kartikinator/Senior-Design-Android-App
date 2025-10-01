package com.example.app.video

import android.graphics.Bitmap

interface FrameInterpolator {
    suspend fun interpolateFrame(previousFrame: Bitmap, nextFrame: Bitmap): Bitmap
}



