package com.chizberg.rewind.domain

import com.chizberg.rewind.core.util.InterpolationPoint
import com.chizberg.rewind.core.util.lerp
import com.chizberg.rewind.core.util.lerpParameter

/**
 * Year -> colour tinting scheme for map annotations. Port of iOS `GradientScheme` +
 * `GradientSchemeValue`. Five schemes; [color] maps a year within the current view's `maxRange`
 * onto the ramp.
 *
 * Divergence from iOS: `rewind`/`pastvu` reference UIKit **system colours** on iOS; here every
 * stop is a fixed sRGB triple (system colours aren't portable and would drift between platforms).
 * The `rewind` stops use the standard iOS light-mode system-colour sRGB values.
 *
 * Serializable (iOS `Codable`) so the chosen scheme persists by name inside the settings blob. No
 * `@Serializable` annotation: kotlinx.serialization handles a plain enum on its own, and adding it
 * here makes the plugin bolt `serializer()` onto the **private** companion below, which the JVM
 * backend then cannot generate an accessor for (an internal compiler error, not a diagnostic).
 */
enum class GradientScheme {
    Rewind,
    Pastvu,
    Warm,
    Ocean,
    Bw,
    ;

    val title: String
        get() =
            when (this) {
                Rewind -> "Rewind"
                Pastvu -> "PastVu"
                Warm -> "Warm"
                Ocean -> "Ocean"
                Bw -> "Black & White"
            }

    val value: List<InterpolationPoint<RgbaColor>>
        get() =
            when (this) {
                Rewind -> REWIND
                Pastvu -> PASTVU
                Warm -> WARM
                Ocean -> OCEAN
                Bw -> BW
            }

    /**
     * A foreground colour to use when the tint is too light for white text (iOS `darkForeground`).
     * Only the light-ended schemes need it; the others fall back to plain black.
     */
    val darkForeground: RgbaColor?
        get() =
            when (this) {
                Rewind, Pastvu, Bw -> null
                Warm -> WARM.first().value
                Ocean -> OCEAN.first().value
            }

    /**
     * A legible foreground (text/badge) colour over a [tint] background: white on dark tints,
     * else the scheme's [darkForeground] or plain black. Port of the iOS annotation `updateColors`
     * choice shared by the image, cluster and merged annotation views.
     */
    fun foreground(tint: RgbaColor): RgbaColor =
        when {
            tint.isDark -> RgbaColor.white
            else -> darkForeground ?: RgbaColor.black
        }

    /** The tint for [year], normalized across [maxRange] and clamped to the ramp ends. */
    fun color(
        year: Int,
        maxRange: IntRange,
    ): RgbaColor {
        val t =
            lerpParameter(
                value = year.toDouble(),
                lowerBound = maxRange.first.toDouble(),
                upperBound = maxRange.last.toDouble(),
            )
        return lerp(t, value)
    }

    private companion object {
        fun stops(vararg points: Pair<Double, RgbaColor>): List<InterpolationPoint<RgbaColor>> =
            points.map { InterpolationPoint(it.first, it.second) }

        /** sRGB from 0..255 components. */
        fun rgb(
            r: Int,
            g: Int,
            b: Int,
        ): RgbaColor = RgbaColor(r / MAX_8BIT, g / MAX_8BIT, b / MAX_8BIT, 1.0)

        /** sRGB from 0..1 components. */
        fun rgb(
            r: Double,
            g: Double,
            b: Double,
        ): RgbaColor = RgbaColor(r, g, b, 1.0)

        const val MAX_8BIT = 255.0

        // iOS light-mode system-colour sRGB values, standing in for systemIndigo/Blue/... etc.
        val REWIND =
            stops(
                0.00 to rgb(88, 86, 214), // systemIndigo
                0.30 to rgb(0, 122, 255), // systemBlue
                0.42 to rgb(175, 82, 222), // systemPurple
                0.48 to rgb(255, 45, 85), // systemPink
                0.65 to rgb(255, 59, 48), // systemRed
                0.76 to rgb(255, 149, 0), // systemOrange
                0.82 to rgb(255, 204, 0), // systemYellow
                1.00 to rgb(52, 199, 89), // systemGreen
            )

        val PASTVU =
            stops(
                0.00 to rgb(0, 0, 102),
                0.30 to rgb(0, 0, 171),
                0.36 to rgb(57, 0, 171),
                0.42 to rgb(114, 0, 171),
                0.48 to rgb(171, 0, 171),
                0.53 to rgb(171, 0, 114),
                0.59 to rgb(171, 0, 57),
                0.65 to rgb(171, 0, 0),
                0.71 to rgb(171, 57, 0),
                0.76 to rgb(171, 114, 0),
                0.82 to rgb(171, 171, 0),
                0.88 to rgb(114, 171, 0),
                0.94 to rgb(57, 171, 0),
                1.00 to rgb(0, 171, 0),
            )

        val WARM =
            stops(
                0.00 to rgb(0.40, 0.05, 0.10),
                0.25 to rgb(0.60, 0.15, 0.15),
                0.50 to rgb(0.80, 0.30, 0.20),
                0.75 to rgb(0.93, 0.60, 0.45),
                1.00 to rgb(1.0, 0.87, 0.78),
            )

        val OCEAN =
            stops(
                0.00 to rgb(0.05, 0.15, 0.35),
                0.25 to rgb(0.12, 0.35, 0.60),
                0.50 to rgb(0.25, 0.52, 0.78),
                0.75 to rgb(0.45, 0.70, 0.88),
                1.00 to rgb(0.68, 0.85, 0.95),
            )

        val BW =
            stops(
                0.00 to RgbaColor.black,
                1.00 to RgbaColor.white,
            )
    }
}
