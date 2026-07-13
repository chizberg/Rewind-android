package com.chizberg.rewind.domain

import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Zoom math. Port of iOS `Zoom.swift`, with an accepted divergence (plan M5): Google Maps'
 * camera zoom is already the whole web-mercator level that iOS reconstructs from the span via
 * `log2(360 / delta)`, so we round + clamp it directly and drop the screen-size adjustment table.
 * JVM-only — no com.google.* here; the camera zoom crosses the UI boundary as a plain Float.
 */

// iOS clamps the reconstructed zoom to 3...19.
private const val MIN_ZOOM = 3
private const val MAX_ZOOM = 19

fun zoom(cameraZoom: Float): Int = cameraZoom.roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM)

/**
 * Longitude degrees spanned by a whole tile at [zoom]. Port of iOS `delta(zoom:mapSize:)` with the
 * screen adjustment dropped. Only used as the clustering cell base (`delta(zoom) / 8`) in M6.
 */
fun delta(zoom: Int): Double = 360.0 / 2.0.pow(zoom)
