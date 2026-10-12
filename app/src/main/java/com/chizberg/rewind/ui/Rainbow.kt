package com.chizberg.rewind.ui

import androidx.compose.ui.graphics.Color
import com.chizberg.rewind.colorization.image.gammaDecode
import com.chizberg.rewind.colorization.image.gammaEncode
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
            red = gammaEncode((gammaDecode(color.red) * scale).coerceIn(0f, 1f)),
            green = gammaEncode((gammaDecode(color.green) * scale).coerceIn(0f, 1f)),
            blue = gammaEncode((gammaDecode(color.blue) * scale).coerceIn(0f, 1f)),
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
