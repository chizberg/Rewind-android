package com.chizberg.rewind.colorization.image

/**
 * An opaque 8-bit RGB image as packed `0xAARRGGBB` ints, row by row — the layout
 * `Bitmap.getPixels` fills and `Bitmap.createBitmap(int[], …)` takes.
 *
 * Android-only: iOS hands `UIImage`/`CGImage` through the pipeline and reads them with Core
 * Graphics. Here the pipeline stays JVM-only (the repo's rule for pure logic), so the photo crosses
 * into it as plain pixels and the `Bitmap` conversions live at the app boundary.
 */
class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(pixels.size == width * height)
    }

    val size: PlaneSize get() = PlaneSize(width, height)

    /** The rows [top] until [top] + [rows], as an image of their own. */
    fun rows(
        top: Int,
        rows: Int,
    ): ArgbImage = ArgbImage(width, rows, pixels.copyOfRange(top * width, (top + rows) * width))

    companion object {
        fun argb(
            red: Int,
            green: Int,
            blue: Int,
        ): Int = OPAQUE or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue

        fun red(pixel: Int): Int = (pixel shr RED_SHIFT) and CHANNEL_MASK

        fun green(pixel: Int): Int = (pixel shr GREEN_SHIFT) and CHANNEL_MASK

        fun blue(pixel: Int): Int = pixel and CHANNEL_MASK

        private const val OPAQUE = 0xFF shl 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val CHANNEL_MASK = 0xFF
    }
}
