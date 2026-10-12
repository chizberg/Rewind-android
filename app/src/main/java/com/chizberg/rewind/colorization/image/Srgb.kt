package com.chizberg.rewind.colorization.image

import kotlin.math.pow

/** The inverse of the sRGB transfer function (IEC 61966-2-1): encoded 0...1 to linear light. */
@Suppress("MagicNumber")
fun gammaDecode(c: Float): Float {
    if (c <= 0.04045f) return c / 12.92f
    return ((c + 0.055f) / 1.055f).pow(2.4f)
}

/** The sRGB transfer function itself (IEC 61966-2-1): linear light 0...1 to encoded. */
@Suppress("MagicNumber")
fun gammaEncode(c: Float): Float {
    if (c <= 0.0031308f) return 12.92f * c
    return 1.055f * c.pow(1 / 2.4f) - 0.055f
}
