package com.chizberg.rewind.features.comparison

import kotlin.math.roundToInt

/**
 * One entry of the zoom picker above the shutter. Port of iOS `Lens` (`title` + `zoomValue`).
 *
 * Divergence, deliberate: iOS reads the *physical* switch-over points of the virtual device
 * (`virtualDeviceSwitchOverVideoZoomFactors` plus the crop factors of each constituent camera) and
 * so its picker names real lenses. CameraX publishes no such list — a bound camera only reports a
 * continuous `minZoomRatio…maxZoomRatio` — so the picker offers *brackets* of that range instead
 * (see [lensBrackets]). Equality is by value here, where iOS keys it on a generated id.
 */
data class Lens(
    val title: String,
    val zoomRatio: Float,
)

/** The stops offered when the camera's range covers them; the everyday ones a phone camera has. */
private val BRACKETS = listOf(0.5f, 1f, 2f, 3f, 5f, 10f)

/** iOS's wide angle camera, the lens the session starts on (`mainLens`). */
const val DEFAULT_ZOOM_RATIO = 1f

/**
 * The lens picker for a camera whose zoom runs [minZoomRatio]…[maxZoomRatio] — the bracket stops
 * inside that range, plus the 1× default itself, ascending. Counterpart of iOS `getAvailableLens`,
 * which has physical lenses to enumerate; this one only has a range (see [Lens]).
 *
 * A single-stop result is what the screen uses to hide the picker altogether (iOS shows it only
 * `availableLens.count > 1`).
 */
fun lensBrackets(
    minZoomRatio: Float,
    maxZoomRatio: Float,
): List<Lens> =
    (BRACKETS + DEFAULT_ZOOM_RATIO)
        .filter { it in minZoomRatio..maxZoomRatio }
        .distinct()
        .sorted()
        .map { Lens(title = zoomLabel(it), zoomRatio = it) }

/** Port of iOS `makeZoomLabel`: one decimal, and no ".0" tail on the whole numbers. */
private fun zoomLabel(zoomRatio: Float): String {
    val rounded = (zoomRatio * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) "${rounded.toInt()}x" else "${rounded}x"
}
