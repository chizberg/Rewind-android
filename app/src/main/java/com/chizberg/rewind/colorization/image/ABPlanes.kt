package com.chizberg.rewind.colorization.image

import com.chizberg.rewind.colorization.lerp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The a and b channels of CIE L*a*b* a colorization model predicts, in OpenCV's float units
 * (-127...127). Port of iOS `ABPlanes`: [a] is negative towards green and positive towards red,
 * [b] negative towards blue and positive towards yellow, both row by row.
 * https://docs.opencv.org/4.x/de/d25/imgproc_color_conversions.html#color_convert_rgb_lab
 */
class ABPlanes(
    val size: PlaneSize,
    val a: FloatArray,
    val b: FloatArray,
) {
    init {
        require(a.size == size.pixelCount && b.size == size.pixelCount)
    }

    /** How far from gray every pixel's color sits, the length of its (a, b): the reference's
     *  np.hypot of the two channels. */
    val chroma: FloatPlane
        get() = FloatPlane(size, FloatArray(size.pixelCount) { hypot(a[it], b[it]) })

    /** How much color the frame has where it is all but at its most colorful: the reference's
     *  np.percentile(chroma, 99). The ceiling holds a frame down to it, and the diagnostic asks it
     *  whether the model found any color at all. */
    val peakChroma: Float
        get() = chroma.percentile(PEAK_PERCENTILE, HISTOGRAM_BINS)

    /** The same colors, weaker or stronger by one factor: every pixel's chroma times it, every
     *  pixel's hue where it was. */
    fun chromaScaled(factor: Float): ABPlanes =
        ABPlanes(
            size,
            FloatArray(a.size) { a[it] * factor },
            FloatArray(b.size) { b[it] * factor },
        )

    /** Keeps the top-left [target] of the model's ab, undoing `padded(target:)` (the reference's
     *  ab[:, :, :uh, :uw]). */
    fun cropped(target: PlaneSize): ABPlanes {
        require(target.width <= size.width && target.height <= size.height)

        fun crop(values: FloatArray): FloatArray =
            FloatArray(target.pixelCount) { i ->
                values[(i / target.width) * size.width + i % target.width]
            }
        return ABPlanes(target, crop(a), crop(b))
    }

    /**
     * Scales the model's ab to the gray frame's size, each pixel blended from its four nearest
     * source pixels (the reference's F.interpolate, mode="bilinear", align_corners=False).
     * https://docs.pytorch.org/docs/stable/generated/torch.nn.functional.interpolate.html
     */
    fun bilinearResized(target: PlaneSize): ABPlanes {
        val columns = neighbors(size.width, target.width)
        val rows = neighbors(size.height, target.height)

        fun resize(values: FloatArray): FloatArray {
            val resized = FloatArray(target.pixelCount)
            for (y in 0 until target.height) {
                val row = rows[y]
                val top = row.low * size.width
                val bottom = row.high * size.width
                for (x in 0 until target.width) {
                    val column = columns[x]
                    val upper =
                        lerp(
                            column.weight,
                            values[top + column.low],
                            values[
                                top +
                                    column.high,
                            ],
                        )
                    val lower =
                        lerp(
                            column.weight,
                            values[bottom + column.low],
                            values[bottom + column.high],
                        )
                    resized[y * target.width + x] = lerp(row.weight, upper, lower)
                }
            }
            return resized
        }
        return ABPlanes(target, resize(a), resize(b))
    }

    /** The two source pixels an output pixel lies between along one axis, and how far it is from
     *  [low] towards [high]. */
    private class Neighbors(
        val low: Int,
        val high: Int,
        val weight: Float,
    )

    private companion object {
        /** The frame is read at its 99th percentile of chroma rather than at its maximum, which
         *  is one pixel and on the parity frames runs to three and a half times the percentile. */
        const val PEAK_PERCENTILE = 99.0

        /** What a model predicts tops out around 160 on the reference's frames, which puts a bin
         *  at a hundredth or two of a chroma unit. */
        const val HISTOGRAM_BINS = 8192

        /** Pixel centers sit half a pixel in; positions past an edge take the edge pixel
         *  (align_corners=False). */
        fun neighbors(
            source: Int,
            destination: Int,
        ): Array<Neighbors> {
            val scale = source.toFloat() / destination
            return Array(destination) { index ->
                val position = max(0f, (index + 0.5f) * scale - 0.5f)
                val low = min(position.toInt(), source - 1)
                Neighbors(low, min(low + 1, source - 1), position - low)
            }
        }
    }
}
