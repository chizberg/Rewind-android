package com.chizberg.rewind.colorization.stages

import com.chizberg.rewind.colorization.image.ABPlanes

/**
 * The limit on how saturated a result may get: a frame whose color reaches past the ceiling is
 * scaled down as a whole until it fits under it. One factor for the entire frame, and never above
 * 1, so no pixel changes hue and nothing gets more color than the model gave it. Port of iOS
 * `ChromaCeiling`; [limit] is public because the reference records the ceiling as a stage.
 */
object ChromaCeiling {
    /** Where the ceiling sits for a model that asks for no gain, in Lab chroma units. It moves
     *  with the gain: held at 24, it would take back most of what a gain of 1.5 had added. The
     *  reference's own cap, 24 * bold. */
    private const val CHROMA_AT_UNIT_BOLDNESS = 24f

    fun apply(
        ab: ABPlanes,
        boldness: Float,
    ): ABPlanes {
        val ceiling = limit(boldness)
        val peak = ab.peakChroma
        if (peak <= ceiling) return ab
        return ab.chromaScaled(ceiling / peak)
    }

    /** The highest chroma a frame from this model may peak at — by the gain the model asked for,
     *  not the one boldness's guard was left with. */
    fun limit(boldness: Float): Float = CHROMA_AT_UNIT_BOLDNESS * boldness
}
