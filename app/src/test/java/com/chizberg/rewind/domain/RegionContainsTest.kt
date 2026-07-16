package com.chizberg.rewind.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Region.contains] longitude folding — the `(Δ + 540) % 360 − 180` arithmetic is invisible at a
 * glance and a naive `|Δlon|` silently rejects everything just across the antimeridian.
 * Expected values are hand-computed great-circle-free longitude distances.
 */
class RegionContainsTest {
    @Test
    fun containsMeasuresLongitudeAcrossAntimeridian() {
        val region = Region(center = Coordinate(0.0, 179.0), span = Span(10.0, 4.0))
        // 179° → −179.5° is 1.5° across the antimeridian (naive |Δ| says 358.5 and rejects).
        assertTrue(region.contains(Coordinate(0.0, -179.5)))
        // 179° → −176° is 5° across — outside the ±2° half-span.
        assertFalse(region.contains(Coordinate(0.0, -176.0)))
    }
}
