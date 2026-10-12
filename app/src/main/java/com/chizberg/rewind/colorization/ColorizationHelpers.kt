package com.chizberg.rewind.colorization

import kotlin.math.floor

/** Helpers shared by the colorization pipeline's stages. Port of iOS `ColorizationHelpers`. */
object ColorizationHelpers {
    /**
     * OpenCV's BORDER_REFLECT_101: an index past the edge folds back without repeating the edge
     * pixel, abcd -> dcb|abcd|cba. A single pixel has nothing to fold into, it is the answer.
     * https://docs.opencv.org/4.x/d2/de8/group__core__array.html#ga247f571aa6244827d3d798f13892da58
     */
    fun mirroredIndex(
        index: Int,
        limit: Int,
    ): Int {
        if (limit <= 1) return 0
        val period = 2 * limit - 2
        var i = index % period
        if (i < 0) i += period
        return if (i < limit) i else period - i
    }

    /**
     * A 0...1 channel as a byte, halves away from zero. cv2 rounds them to even, but a gamma curve
     * almost never lands on an exact half: the parity gray frame comes out identical either way.
     */
    fun byte(sRGB: Float): Int = roundAway(sRGB.coerceIn(0f, 1f) * BYTE_MAX).toInt()
}

/**
 * Swift's `rounded()`: halves away from zero. Not Kotlin's `round`, which sends them to even and
 * would move a pixel in every few hundred on the parity frames.
 */
internal fun roundAway(value: Float): Float {
    val half = 0.5f
    return if (value >= 0f) floor(value + half) else -floor(-value + half)
}

internal fun roundAway(value: Double): Double {
    val half = 0.5
    return if (value >= 0.0) floor(value + half) else -floor(-value + half)
}

/** VGSL `lerp(at:between:_:)`. */
internal fun lerp(
    at: Float,
    lhs: Float,
    rhs: Float,
): Float = lhs + (rhs - lhs) * at

/** iOS `lerpParameter(of:lowerBound:upperBound:)`: where [value] sits between the bounds, held to
 *  0...1 (a NaN comes out 0, as there). */
internal fun lerpParameter(
    value: Float,
    lowerBound: Float,
    upperBound: Float,
): Float =
    when {
        !(value > lowerBound) -> 0f
        !(value < upperBound) -> 1f
        else -> (value - lowerBound) / (upperBound - lowerBound)
    }

internal const val BYTE_MAX = 255f
