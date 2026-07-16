package com.chizberg.rewind.domain

import kotlin.math.roundToInt

/*
 * Zoom math. Port of iOS `Zoom.swift`, with an accepted divergence (plan M5): Google Maps'
 * camera zoom is already the whole web-mercator level that iOS reconstructs from the span via
 * `log2(360 / delta)`, so we round + clamp it directly and drop the screen-size adjustment table.
 * JVM-only — no com.google.* here; the camera zoom crosses the UI boundary as a plain Float.
 *
 * iOS's `delta(zoom:mapSize:)` is deliberately NOT ported: its screen adjustment makes it
 * reconstruct the visible span, which the clustering used as its cell base. Android has the real
 * visible span from the camera, so LocalClustering derives the cell from `region.span` — but snaps
 * it to this rounded [zoom] (via the raw camera zoom) so the grid stays fixed within a bucket, the
 * way iOS's rounded-zoom `delta` does. See LocalClustering's cell-size comment.
 */

// iOS clamps the reconstructed zoom to 3...19.
private const val MIN_ZOOM = 3
private const val MAX_ZOOM = 19

fun zoom(cameraZoom: Float): Int = cameraZoom.roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
