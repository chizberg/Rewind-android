package com.chizberg.rewind.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of iOS GradientSchemeTests: the year -> colour pipeline
 * (`GradientScheme.color` = lerpParameter -> clamp -> binSearch -> per-channel lerp) and
 * `RgbaColor.isDark` (gamma-linearised luminance).
 *
 * Expected colours are hand-computed from the interpolation math against the explicit-RGBA schemes
 * (`Bw`, `Warm`) so the expected value is external, not a re-run of the product transform.
 * `Rewind`/`Pastvu` are skipped on purpose (their stops are a fixed-sRGB divergence, not a spec).
 */
private const val TOL = 1e-9

class GradientSchemeTest {
    private fun expectColor(
        color: RgbaColor,
        r: Double,
        g: Double,
        b: Double,
        a: Double = 1.0,
    ) {
        assertEquals(r, color.red, TOL)
        assertEquals(g, color.green, TOL)
        assertEquals(b, color.blue, TOL)
        assertEquals(a, color.alpha, TOL)
    }

    // Bw = [(0, black), (1, white)]; the year lands exactly on each endpoint.
    @Test
    fun endpointsAreExactStops() {
        expectColor(GradientScheme.Bw.color(year = 1826, maxRange = 1826..2000), 0.0, 0.0, 0.0)
        expectColor(GradientScheme.Bw.color(year = 2000, maxRange = 1826..2000), 1.0, 1.0, 1.0)
    }

    // Years outside maxRange must clamp to the boundary stop, never extrapolate.
    @Test
    fun clampsOutsideRange() {
        expectColor(GradientScheme.Bw.color(year = 1700, maxRange = 1826..2000), 0.0, 0.0, 0.0)
        expectColor(GradientScheme.Bw.color(year = 2100, maxRange = 1826..2000), 1.0, 1.0, 1.0)
    }

    // t = (1913 - 1826) / (2000 - 1826) = 87/174 = 0.5 -> mid grey on the bw ramp.
    @Test
    fun bwMidpointIsGrey() {
        expectColor(GradientScheme.Bw.color(year = 1913, maxRange = 1826..2000), 0.5, 0.5, 0.5)
    }

    // t = 375/1000 = 0.375 lands between warm stops at 0.25 and 0.50, at t1 = 0.5.
    // r = lerp(.5, .60, .80) = .70; g = lerp(.5, .15, .30) = .225; b = lerp(.5, .15, .20) = .175.
    @Test
    fun warmInteriorSegmentInterpolates() {
        expectColor(GradientScheme.Warm.color(year = 375, maxRange = 0..1000), 0.70, 0.225, 0.175)
    }

    // isDark uses sRGB-gamma-linearised relative luminance, not raw channel means. Black/white are
    // the obvious ends; the greys straddle the perceptual 0.5 threshold and would flip if the gamma
    // linearisation were dropped: mid grey linearises to ~0.21 (dark), 0.75 grey to ~0.52 (light).
    @Test
    fun isDarkUsesLinearisedLuminance() {
        assertTrue(RgbaColor(0.0, 0.0, 0.0, 1.0).isDark)
        assertFalse(RgbaColor(1.0, 1.0, 1.0, 1.0).isDark)
        assertTrue(RgbaColor(0.5, 0.5, 0.5, 1.0).isDark)
        assertFalse(RgbaColor(0.75, 0.75, 0.75, 1.0).isDark)
    }
}
