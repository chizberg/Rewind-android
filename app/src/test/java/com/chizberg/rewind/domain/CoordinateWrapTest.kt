package com.chizberg.rewind.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirror of iOS CoordinateWrapTests: antimeridian / pole normalization. Expected outputs are
 * hand-worked from the algorithm and asserted with a tolerance. `reversed()` is not tested on
 * its own — its effect is pinned end-to-end by NetworkParsingTest.modelClusterReverses...
 */
private const val TOL = 1e-9

class CoordinateWrapTest {
    private fun expectWrap(
        lat: Double,
        lon: Double,
        toLat: Double,
        toLon: Double,
    ) {
        val wrapped = Coordinate(lat, lon).wrap()
        assertEquals(toLat, wrapped.latitude, TOL)
        assertEquals(toLon, wrapped.longitude, TOL)
    }

    @Test
    fun inRangeIsIdentity() = expectWrap(lat = 55.75, lon = 37.62, toLat = 55.75, toLon = 37.62)

    @Test
    fun longitudeOverflowWraps() = expectWrap(lat = 10.0, lon = 190.0, toLat = 10.0, toLon = -170.0)

    @Test
    fun longitudeUnderflowWraps() =
        expectWrap(lat = 10.0, lon = -190.0, toLat = 10.0, toLon = 170.0)

    @Test
    fun overNorthPoleFlipsLatAndLon() =
        expectWrap(lat = 100.0, lon = 20.0, toLat = 80.0, toLon = -160.0)

    @Test
    fun overSouthPoleFlipsLatAndLon() =
        expectWrap(lat = -100.0, lon = 20.0, toLat = -80.0, toLon = -160.0)
}
