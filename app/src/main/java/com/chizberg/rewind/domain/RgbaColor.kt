package com.chizberg.rewind.domain

import com.chizberg.rewind.core.util.Interpolatable
import com.chizberg.rewind.core.util.lerp
import kotlin.math.pow

/**
 * A plain sRGB colour with straight (non-premultiplied) alpha, components in `[0, 1]`.
 * Port of VGSL `RGBAColor` plus Rewind's `RGBAColor.isDark`. JVM-only: no `android.graphics` —
 * the render layer converts to an Android colour int at the UI edge, keeping tinting testable.
 */
data class RgbaColor(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double,
) : Interpolatable<RgbaColor> {
    override fun lerp(
        at: Double,
        lhs: RgbaColor,
        rhs: RgbaColor,
    ): RgbaColor =
        RgbaColor(
            red = lerp(at, lhs.red, rhs.red),
            green = lerp(at, lhs.green, rhs.green),
            blue = lerp(at, lhs.blue, rhs.blue),
            alpha = lerp(at, lhs.alpha, rhs.alpha),
        )

    /**
     * Whether the colour reads as dark, by WCAG relative luminance over sRGB-gamma-linearised
     * channels (not raw channel means). Port of Rewind `RGBAColor.isDark`; drives the choice of a
     * legible foreground/shadow over a tinted marker.
     */
    val isDark: Boolean
        get() {
            fun linearize(c: Double): Double =
                if (c <= GAMMA_THRESHOLD) {
                    c / GAMMA_LINEAR_DIVISOR
                } else {
                    ((c + GAMMA_OFFSET) / GAMMA_SCALE).pow(GAMMA_EXPONENT)
                }

            val luminance =
                LUMA_R * linearize(red) + LUMA_G * linearize(green) + LUMA_B * linearize(blue)
            return luminance < DARK_LUMINANCE_THRESHOLD
        }

    companion object {
        val black = RgbaColor(0.0, 0.0, 0.0, 1.0)
        val white = RgbaColor(1.0, 1.0, 1.0, 1.0)

        private const val GAMMA_THRESHOLD = 0.03928
        private const val GAMMA_LINEAR_DIVISOR = 12.92
        private const val GAMMA_OFFSET = 0.055
        private const val GAMMA_SCALE = 1.055
        private const val GAMMA_EXPONENT = 2.4
        private const val LUMA_R = 0.2126
        private const val LUMA_G = 0.7152
        private const val LUMA_B = 0.0722
        private const val DARK_LUMINANCE_THRESHOLD = 0.5
    }
}
