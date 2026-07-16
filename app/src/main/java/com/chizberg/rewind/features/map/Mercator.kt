package com.chizberg.rewind.features.map

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Web-Mercator world projection as Google Maps defines it: at zoom `z` the world is a square of
 * side 256·2^z dp (the UI multiplies by screen density for pixels). A marker's screen position is
 * `screenCenter + (world(marker) − world(cameraTarget)) · density`, exact while rotation and tilt
 * are disabled (they are — see RewindMap's `MapUiSettings`).
 *
 * Exists so the annotation overlay can place markers without `Projection.toScreenLocation`: each
 * of those is a Play-services round-trip allocating a fresh `Projection`, and the overlay used to
 * make two of them per marker per frame during a pan — the audited cause of dropped frames. This
 * math keeps the per-frame pan path free of Play-services calls entirely.
 *
 * JVM-only (no android.* / com.google.*), tested against hand-computed spec values in MercatorTest.
 */
object Mercator {
    // The world tile at zoom 0 is 256dp; each zoom level doubles it.
    private const val TILE_DP = 256.0

    // Google's spec clamps sin(latitude) so the poles project to finite y.
    private const val MAX_SIN = 0.9999

    /** The world's side length in dp at [zoom] (fractional camera zooms welcome). */
    fun worldSize(zoom: Double): Double = TILE_DP * 2.0.pow(zoom)

    /** X in world-dp at [zoom]; 0 at longitude −180, grows eastward. */
    fun worldX(
        longitude: Double,
        zoom: Double,
    ): Double = worldSize(zoom) * (longitude / DEGREES_MAX + HALF)

    /** Y in world-dp at [zoom]; 0 at the mercator top (≈85.05°N), grows southward. */
    fun worldY(
        latitude: Double,
        zoom: Double,
    ): Double {
        val sinY = sin(latitude * PI / DEGREES_HALF).coerceIn(-MAX_SIN, MAX_SIN)
        return worldSize(zoom) * (HALF - ln((1 + sinY) / (1 - sinY)) / (4.0 * PI))
    }

    private const val DEGREES_MAX = 360.0
    private const val DEGREES_HALF = 180.0
    private const val HALF = 0.5
}
