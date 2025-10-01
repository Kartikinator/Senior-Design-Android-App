package com.example.app.video

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MockFrameInterpolator : FrameInterpolator {
    override suspend fun interpolateFrame(previousFrame: Bitmap, nextFrame: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val width = previousFrame.width
        val height = previousFrame.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val prevPixels = IntArray(width * height)
        val nextPixels = IntArray(width * height)
        previousFrame.getPixels(prevPixels, 0, width, 0, 0, width, height)
        nextFrame.getPixels(nextPixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(width * height)
        var i = 0
        while (i < outPixels.size) {
            val p = prevPixels[i]
            val n = nextPixels[i]
            val pa = (p ushr 24) and 0xFF
            val pr = (p ushr 16) and 0xFF
            val pg = (p ushr 8) and 0xFF
            val pb = p and 0xFF

            val na = (n ushr 24) and 0xFF
            val nr = (n ushr 16) and 0xFF
            val ng = (n ushr 8) and 0xFF
            val nb = n and 0xFF

            val ra = (pa + na) / 2
            val rr = (pr + nr) / 2
            val rg = (pg + ng) / 2
            val rb = (pb + nb) / 2

            outPixels[i] = (ra shl 24) or (rr shl 16) or (rg shl 8) or rb
            i++
        }

        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        result
    }
}



