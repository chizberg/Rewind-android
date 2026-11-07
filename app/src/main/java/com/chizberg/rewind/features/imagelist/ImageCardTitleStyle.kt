package com.chizberg.rewind.features.imagelist

import com.chizberg.rewind.colorization.image.gammaEncode
import kotlin.math.roundToInt

sealed interface ImageCardTitleStyle {
    data object Plain : ImageCardTitleStyle

    data class Dimmed(
        val opacity: Float,
    ) : ImageCardTitleStyle

    data object Dark : ImageCardTitleStyle

    companion object {
        fun of(luminances: FloatArray): ImageCardTitleStyle {
            if (luminances.isEmpty()) return Plain
            val sorted = luminances.sorted()
            val darkish = sorted.percentile(DARKISH)
            val lightish = sorted.percentile(LIGHTISH)
            return when {
                darkish >= DARK_MIN_LUMINANCE -> Dark
                lightish <= PLAIN_MAX_LUMINANCE -> Plain
                else ->
                    Dimmed(
                        (1 - gammaEncode(PLAIN_MAX_LUMINANCE) / gammaEncode(lightish))
                            .coerceIn(0f, MAX_DIMMING),
                    )
            }
        }
    }
}

private fun List<Float>.percentile(fraction: Float): Float =
    this[((size - 1) * fraction).roundToInt()]

private const val DARKISH = 0.25f
private const val LIGHTISH = 0.75f
private const val DARK_MIN_LUMINANCE = 0.45f
private const val PLAIN_MAX_LUMINANCE = 0.18f
private const val MAX_DIMMING = 0.6f
