package com.chizberg.rewind.ui

import android.graphics.Bitmap
import com.chizberg.rewind.colorization.image.ArgbImage

/**
 * The colorization pipeline's pixels and Android's bitmaps, both ways. The pipeline stays JVM-only
 * (see [ArgbImage]); this is the boundary it crosses.
 */
fun ArgbImage.toBitmap(): Bitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

/** The bitmap's pixels as the pipeline reads them. A hardware bitmap is copied out first. */
fun Bitmap.toArgbImage(): ArgbImage {
    val readable =
        if (config ==
            Bitmap.Config.HARDWARE
        ) {
            copy(Bitmap.Config.ARGB_8888, false)
        } else {
            this
        }
    val pixels = IntArray(readable.width * readable.height)
    readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
    return ArgbImage(readable.width, readable.height, pixels)
}
