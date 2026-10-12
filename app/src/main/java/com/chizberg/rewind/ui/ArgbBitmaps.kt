package com.chizberg.rewind.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.chizberg.rewind.colorization.image.ArgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap

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

/**
 * [image] as a bitmap to draw, made off the main thread and once per pixel buffer: the colorized
 * photo shows on the details screen, in the fullscreen viewer and on the comparison canvas, and a
 * 12-MP photo is tens of milliseconds and 48 MB per copy. Null until the first conversion is done;
 * the bitmap lives as long as the buffer it was made from.
 */
@Composable
fun rememberImageBitmap(image: ArgbImage?): ImageBitmap? {
    val bitmap by produceState(image?.let { bitmaps[it] }, image) {
        value =
            image?.let {
                bitmaps[it] ?: withContext(Dispatchers.Default) {
                    it.toBitmap().asImageBitmap()
                }.also { made -> bitmaps[it] = made }
            }
    }
    return bitmap
}

/** Weak keys: a buffer the screens let go of takes its bitmap with it. [ArgbImage] keeps
 *  identity equality, so two buffers never share an entry. */
private val bitmaps: MutableMap<ArgbImage, ImageBitmap> =
    Collections.synchronizedMap(WeakHashMap())
