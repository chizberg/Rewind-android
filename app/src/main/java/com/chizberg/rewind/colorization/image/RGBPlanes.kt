package com.chizberg.rewind.colorization.image

import com.chizberg.rewind.colorization.BYTE_MAX
import com.chizberg.rewind.colorization.ColorizationHelpers
import com.chizberg.rewind.colorization.roundAway
import kotlin.math.max
import kotlin.math.min

/** A photo as three 0...1 float channels. Port of iOS `RGBPlanes`. */
class RGBPlanes(
    val size: PlaneSize,
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
) {
    init {
        require(r.size == size.pixelCount && g.size == size.pixelCount && b.size == size.pixelCount)
    }

    /** The composed photo as pixels, each 0...1 channel rounded to a byte. iOS `makeUIImage()`. */
    fun toArgbImage(): ArgbImage =
        ArgbImage(
            size.width,
            size.height,
            IntArray(size.pixelCount) { i ->
                ArgbImage.argb(
                    ColorizationHelpers.byte(r[i]),
                    ColorizationHelpers.byte(g[i]),
                    ColorizationHelpers.byte(b[i]),
                )
            },
        )

    companion object {
        /**
         * The photo read at a long side of at most [maxSide], each channel a byte over 255. iOS
         * `init(cgImage:maxSide:)`.
         *
         * Divergence: iOS shrinks by drawing into a smaller `CGContext` at `.high` interpolation,
         * whose filter Core Graphics does not document; this averages the pixels each output pixel
         * covers (cv2.INTER_AREA, the same taps the gray frame's own resize uses) and rounds back
         * to bytes, as the context's 8-bit buffer does. The parity frames are read at their own
         * size, so neither touches them.
         */
        fun of(
            image: ArgbImage,
            maxSide: Int,
        ): RGBPlanes {
            val scale = min(1.0, maxSide.toDouble() / max(image.width, image.height))
            val width = max(1, roundAway(image.width * scale).toInt())
            val height = max(1, roundAway(image.height * scale).toInt())
            val target = PlaneSize(width, height)

            fun channel(read: (Int) -> Int): FloatArray {
                val bytes =
                    GrayPlane.of(
                        image.size,
                        IntArray(image.size.pixelCount) { read(image.pixels[it]) },
                    )
                val sized = if (target == image.size) bytes else bytes.resized(target)
                return FloatArray(target.pixelCount) { sized[it] / BYTE_MAX }
            }
            return RGBPlanes(
                target,
                channel(ArgbImage::red),
                channel(ArgbImage::green),
                channel(ArgbImage::blue),
            )
        }
    }
}
