package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ArgbImage
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.image.resized

/**
 * A PastVu original cut into the photo and the "uploaded by" strip PastVu burns under it, so only
 * the photo goes through the model and the strip comes back unchanged. Port of iOS
 * `WatermarkedImage`, on pixels rather than `UIImage`s.
 */
class WatermarkedImage(
    val content: ArgbImage,
    val watermark: ArgbImage?,
) {
    /** The strip back under [content], scaled to its width — the colorized photo comes back at
     *  the pipeline's working size, which is not the original's. */
    fun stitched(): ArgbImage {
        val watermark = watermark ?: return content
        val stripHeight =
            roundAway(content.width.toDouble() * watermark.height / watermark.width).toInt()
        val strip = watermark.resized(PlaneSize(content.width, stripHeight))
        return ArgbImage(content.width, content.height + stripHeight, content.pixels + strip.pixels)
    }

    /** With [content] swapped for another photo of the same frame — the colorized one. */
    fun withContent(content: ArgbImage): WatermarkedImage = WatermarkedImage(content, watermark)
}

/**
 * Cuts the strip off the bottom of [image]. PastVu reports both heights for the original; a file of
 * any other height (a cached rendition, a photo with no strip) comes back whole, with no watermark.
 * Port of iOS `splitWatermark(from:watermarkHeight:contentHeight:)`.
 */
fun splitWatermark(
    image: ArgbImage,
    watermarkHeight: Int,
    contentHeight: Int,
): WatermarkedImage {
    if (watermarkHeight <= 0 || image.height != contentHeight + watermarkHeight) {
        return WatermarkedImage(image, null)
    }
    return WatermarkedImage(
        image.rows(0, contentHeight),
        image.rows(contentHeight, watermarkHeight),
    )
}

/**
 * Each channel through the gray frame's area resize. iOS lets Core Graphics draw the strip into
 * its rectangle at the default interpolation; this is the pipeline's own resampler instead, the
 * strip being plain text on a flat ground either way.
 */
private fun ArgbImage.resized(target: PlaneSize): ArgbImage {
    if (target == size) return this

    fun channel(read: (Int) -> Int): GrayPlane =
        GrayPlane
            .of(
                size,
                IntArray(pixels.size) {
                    read(pixels[it])
                },
            ).resized(target)
    val r = channel(ArgbImage::red)
    val g = channel(ArgbImage::green)
    val b = channel(ArgbImage::blue)
    return ArgbImage(
        target.width,
        target.height,
        IntArray(target.pixelCount) {
            ArgbImage.argb(r[it], g[it], b[it])
        },
    )
}
