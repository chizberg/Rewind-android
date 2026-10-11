package com.chizberg.rewind.colorization.stages

import com.chizberg.rewind.colorization.ColorizationHelpers
import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.lerpParameter
import com.chizberg.rewind.colorization.roundAway
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A gain on the predicted color, weighted so it lifts only the pixels whose lightness can carry
 * color, and withdrawn on a frame the model has painted in one flat tint. Port of iOS `Boldness`;
 * the three steps [apply] is built from are public because the reference records each as a stage
 * of its own, and the parity test pins them one by one.
 */
object Boldness {
    /** The gain is off at or below L=20, full between L=35 and L=75, and off again at or above
     *  L=90. The reference's own bounds, lum_weight(lo=20, hi=90, soft=15). */
    private const val LIGHTNESS_LOW = 20f
    private const val LIGHTNESS_HIGH = 90f
    private const val LIGHTNESS_SOFT = 15f

    /** Where the guard starts withdrawing the gain and where it is gone, on the cast ratio. The
     *  reference's apply_boldness(c_lo=0.75, c_hi=0.95). */
    private const val CAST_GUARD_LOW = 0.75f
    private const val CAST_GUARD_HIGH = 0.95f

    /** How wide the weight map's blur reaches on a frame of the reference's long side, in px. */
    private const val RADIUS_AT_REFERENCE = 24.0

    /** This port's own floor, like the bilateral's: it binds at 233 px and below. */
    private const val MINIMUM_RADIUS = 4

    /** A frame this close to gray has no color direction to measure. */
    private const val NEGLIGIBLE_CHROMA = 1e-6

    /** Close enough to 1 that the weight map is not worth building. */
    private const val NEGLIGIBLE_GAIN = 1e-3f

    fun apply(
        ab: ABPlanes,
        lightness: FloatPlane,
        boldness: Float,
    ): ABPlanes {
        require(ab.size == lightness.size)
        val applied = effectiveBoldness(boldness, castRatio(ab))
        if (abs(applied - 1) < NEGLIGIBLE_GAIN) return ab
        val weight = lightnessWeight(lightness).values
        val a = FloatArray(ab.a.size)
        val b = FloatArray(ab.b.size)
        for (i in a.indices) {
            val gain = 1 + (applied - 1) * weight[i]
            a[i] = ab.a[i] * gain
            b[i] = ab.b[i] * gain
        }
        return ABPlanes(ab.size, a, b)
    }

    /** |mean ab| over mean chroma: how much of the frame's color points one way. At 1 every
     *  pixel points the same way, a tint laid over the photo rather than a colorization. Double
     *  sums, because a Float one loses the low bits over millions of additions. */
    fun castRatio(ab: ABPlanes): Float {
        var sumA = 0.0
        var sumB = 0.0
        var sumChroma = 0.0
        for (i in ab.a.indices) {
            sumA += ab.a[i]
            sumB += ab.b[i]
            sumChroma += hypot(ab.a[i], ab.b[i])
        }
        val count = ab.size.pixelCount.toDouble()
        val meanChroma = sumChroma / count
        if (meanChroma < NEGLIGIBLE_CHROMA) return 1f
        return (hypot(sumA / count, sumB / count) / meanChroma).toFloat()
    }

    /** The requested gain, withdrawn as the frame approaches a single tint. The guard only ever
     *  attenuates. */
    fun effectiveBoldness(
        requested: Float,
        castRatio: Float,
    ): Float {
        val rolloff = lerpParameter(castRatio, CAST_GUARD_LOW, CAST_GUARD_HIGH)
        return 1 + (requested - 1) * (1 - rolloff)
    }

    /** 1 in the midtones, tapering to 0 in deep shadow and blown highlight, then blurred wide.
     *  Unblurred, the map is a function of L, so it would multiply the lightness texture straight
     *  into the color. */
    fun lightnessWeight(lightness: FloatPlane): FloatPlane {
        val window =
            lightness.map { value ->
                lerpParameter(value - LIGHTNESS_LOW, 0f, LIGHTNESS_SOFT) *
                    lerpParameter(LIGHTNESS_HIGH - value, 0f, LIGHTNESS_SOFT)
            }
        // The reference rounds halves to even, as Python does, and this rounds them away from
        // zero as the rest of the pipeline does; the two land a pixel apart at a handful of long
        // sides (300, 700, 1100, 1500, 1900).
        val radius = roundAway(RADIUS_AT_REFERENCE * lightness.size.scaleFromReference)
        return window.boxBlurred(max(MINIMUM_RADIUS, radius.toInt()))
    }
}

/**
 * cv2.blur over a (2r+1) square: separable, running sums, BORDER_REFLECT_101. A plain average,
 * unlike the other blur in this pipeline: here an edge is the defect being erased.
 * https://docs.opencv.org/4.x/d4/d86/group__imgproc__filter.html#ga8c45db9afe636703801b0b2e440fce37
 */
private fun FloatPlane.boxBlurred(radius: Int): FloatPlane {
    val width = size.width
    val height = size.height
    val divisor = (2 * radius + 1).toFloat()

    fun mirrored(
        index: Int,
        limit: Int,
    ) = ColorizationHelpers.mirroredIndex(index, limit)

    val rows = FloatArray(size.pixelCount)
    for (y in 0 until height) {
        val row = y * width
        var sum = 0f
        for (offset in -radius..radius) sum += values[row + mirrored(offset, width)]
        for (x in 0 until width) {
            rows[row + x] = sum / divisor
            sum +=
                values[row + mirrored(x + radius + 1, width)] -
                values[row + mirrored(x - radius, width)]
        }
    }

    val blurred = FloatArray(size.pixelCount)
    for (x in 0 until width) {
        var sum = 0f
        for (offset in -radius..radius) sum += rows[mirrored(offset, height) * width + x]
        for (y in 0 until height) {
            blurred[y * width + x] = sum / divisor
            sum +=
                rows[mirrored(y + radius + 1, height) * width + x] -
                rows[mirrored(y - radius, height) * width + x]
        }
    }
    return FloatPlane(size, blurred)
}
