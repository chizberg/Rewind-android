package com.chizberg.rewind.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * iOS `makeRainbowGradient(exposureAdjust:)`: red to red around the color wheel, the colorize
 * feature's signature. iOS's system colors become fixed sRGB here (the light-mode values), and
 * `exposureAdjust` is applied the way SwiftUI defines it — a scale of the linear light by
 * 2^[exposure] (iOS 26 only there; every Android version here).
 */
fun rainbowColors(exposure: Float = 2f): List<Color> =
    RainbowBase.map { color ->
        val scale = 2f.pow(exposure)
        Color(
            red = encode(decode(color.red) * scale),
            green = encode(decode(color.green) * scale),
            blue = encode(decode(color.blue) * scale),
            alpha = color.alpha,
        )
    }

private val RainbowBase =
    listOf(
        Color(0xFFFF3B30),
        Color(0xFFFF9500),
        Color(0xFFFFCC00),
        Color(0xFF34C759),
        Color(0xFF007AFF),
        Color(0xFFAF52DE),
        Color(0xFFFF3B30),
    )

@Suppress("MagicNumber")
private fun decode(c: Float): Float {
    if (c <= 0.04045f) return c / 12.92f
    return ((c + 0.055f) / 1.055f).pow(2.4f)
}

@Suppress("MagicNumber")
private fun encode(linear: Float): Float {
    val c = linear.coerceIn(0f, 1f)
    return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1 / 2.4f) - 0.055f
}
