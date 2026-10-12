package com.chizberg.rewind.colorization.stages

import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.lerpParameter

/**
 * The photo's lightness spread back over the whole scale: the band between its first and its
 * ninety-ninth percentile is stretched onto 0...100. Everything downstream reads this plane — the
 * model, which returns a flat sepia tint on a dark and flat frame, the two post-process stages that
 * weigh color by lightness, and the composed result. The one stage that changes the photo's own
 * brightness rather than only steering color. Port of iOS `Levels` (the reference's
 * stretch_levels); the iOS README's "Levels" has the rest.
 */
object Levels {
    /** Percentiles rather than the darkest and brightest pixel: one speck of dust at either end
     *  would otherwise set the range for the whole frame. The reference's own bounds. */
    private const val LOW_PERCENTILE = 1.0
    private const val HIGH_PERCENTILE = 99.0

    /** At the widest — the whole of L — one bin is 0.0122 of a unit, far under a step of an 8-bit
     *  gray (0.27 to 0.51 of a unit). */
    private const val HISTOGRAM_BINS = 8192

    /** The top of the L* scale, which is what the band is stretched onto. */
    private const val LIGHTNESS_TOP = 100f

    fun stretch(lightness: FloatPlane): FloatPlane {
        val low = lightness.percentile(LOW_PERCENTILE, HISTOGRAM_BINS)
        val high = lightness.percentile(HIGH_PERCENTILE, HISTOGRAM_BINS)
        // A frame of a single tone has no band to stretch, and dividing by its width would send
        // every pixel of it to black.
        if (high <= low) return lightness
        return lightness.map { LIGHTNESS_TOP * lerpParameter(it, low, high) }
    }
}
