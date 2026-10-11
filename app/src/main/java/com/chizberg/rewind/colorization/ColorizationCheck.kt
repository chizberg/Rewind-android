package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ABPlanes
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max

/**
 * What a colorization came back as: worth showing, or one of the two ways it fails. A model given a
 * photo it can make nothing of does not say so — it returns a frame that is all but gray, or one
 * tint laid over the whole of it, and both look to a user like a broken app rather than a limit of
 * the model. Port of iOS `ColorizationCheck`; the iOS README's "Did the model do its job".
 */
enum class ColorizationCheck {
    /** The color is worth showing as it is. */
    Ok,

    /** The model found almost no color: the result is the photo with a hint of tint on it. */
    NoColor,

    /** The model painted the whole photo one color instead of coloring the things in it. */
    OnlyTint,
}

/** The chroma of the frame's most colorful pixels under which there is nothing to show. For
 *  scale, the six parity runs peak between 32 and 87. */
private const val COLORLESS_PEAK = 8f

/** How much chroma a pixel needs before its hue is worth counting (the reference's chroma_min). */
private const val COLORED_CHROMA = 5f

/** 36 bins of 10 degrees, and a window of 6 of them: 60 degrees (bins=36, window=6). */
private const val HUE_BINS = 36
private const val WINDOW_BINS = 6

/** Spread evenly around the circle the colored pixels would put a sixth of themselves in any 60
 *  degrees; past this share in one window the frame is a tint. */
private const val TINTED_SHARE = 0.85f

/** With fewer colored pixels than this the share would be read off a handful of them, and the
 *  frame is called a tint instead of measured (the reference's concentration of 1). */
private const val LEAST_COLORED_PIXELS = 64

/**
 * Whether the color of this frame is worth showing. Asked of what the model predicted rather than
 * of what compose is handed, because both bounds above are absolute while the post-process rescales
 * the whole frame by constants of ours; the reference's bounds were measured where this asks them.
 */
fun ABPlanes.checkColorization(): ColorizationCheck =
    when {
        peakChroma < COLORLESS_PEAK -> ColorizationCheck.NoColor
        hueConcentration() > TINTED_SHARE -> ColorizationCheck.OnlyTint
        else -> ColorizationCheck.Ok
    }

/** The largest share of the colored pixels any 60 degrees of hue holds: 1 when every one of them is
 *  the same color, a sixth when they are spread evenly around the circle. */
private fun ABPlanes.hueConcentration(): Float {
    val bins = IntArray(HUE_BINS)
    var colored = 0
    val chroma = chroma.values
    for (index in chroma.indices) {
        if (!(chroma[index] > COLORED_CHROMA)) continue
        // atan2 answers in -pi...pi, and a full turn added to that puts every hue on one circle
        // before it is binned, the reference's degrees % 360.
        val turn = atan2(b[index], a[index]) / (2 * PI.toFloat()) + 1
        bins[(turn * HUE_BINS).toInt() % HUE_BINS] += 1
        colored += 1
    }
    if (colored < LEAST_COLORED_PIXELS) return 1f

    var largest = 0
    for (start in 0 until HUE_BINS) {
        var sum = 0
        for (bin in start until start + WINDOW_BINS) sum += bins[bin % HUE_BINS]
        largest = max(largest, sum)
    }
    return largest.toFloat() / colored
}
