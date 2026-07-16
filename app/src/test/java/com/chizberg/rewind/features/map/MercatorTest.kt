package com.chizberg.rewind.features.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Mercator] against the published Web-Mercator spec (world = 256·2^zoom dp;
 * x = world·(lon/360 + 0.5); y = world·(0.5 − ln((1+sin φ)/(1−sin φ))/4π)). Expected values are
 * hand-computed from the spec formulas, independently of the implementation — a wrong sign, a
 * degree/radian mix-up, a 2π-for-4π, or a lost 0.5 offset all mis-place every marker silently.
 */
class MercatorTest {
    @Test
    fun worldEdgesAtZoomZero() {
        assertEquals(128.0, Mercator.worldX(0.0, 0.0), EPS)
        assertEquals(128.0, Mercator.worldY(0.0, 0.0), EPS)
        assertEquals(256.0, Mercator.worldX(180.0, 0.0), EPS)
        assertEquals(0.0, Mercator.worldX(-180.0, 0.0), EPS)
        // Mercator's top latitude 2·atan(e^π) − 90° = 85.05112878°: the ln-term is exactly 2π,
        // so y lands on the world's top edge.
        assertEquals(0.0, Mercator.worldY(85.05112878, 0.0), EPS)
    }

    @Test
    fun cityCoordinateMatchesSpec() {
        // Prague (50.0755°N, 14.4378°E) at zoom 0, computed by hand from the spec formulas.
        assertEquals(138.26688, Mercator.worldX(14.4378, 0.0), EPS)
        assertEquals(86.7374715424155, Mercator.worldY(50.0755, 0.0), EPS)
    }

    private companion object {
        const val EPS = 1e-6
    }
}
