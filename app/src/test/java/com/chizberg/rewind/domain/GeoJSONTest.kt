package com.chizberg.rewind.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirror of iOS GeoJSONTests: Region.geoJsonCoordinates — the [lon, lat] swap, the 5-point
 * closed ring, and the corner ordering (center ± half-span).
 */
private const val TOL = 1e-9

class GeoJSONTest {
    private fun makeCoordinates(): List<List<Double>> =
        Region(
            center = Coordinate(latitude = 50.0, longitude = 14.0),
            span = Span(latitudeDelta = 0.2, longitudeDelta = 0.4),
        ).geoJsonCoordinates

    @Test
    fun ringHasFiveClosedPoints() {
        val coords = makeCoordinates()
        assertEquals(5, coords.size)
        // Each point is [lon, lat] — exactly 2 elements.
        coords.forEach { assertEquals(2, it.size) }
        // Closed ring: first == last.
        assertEquals(coords[0][0], coords[4][0], TOL)
        assertEquals(coords[0][1], coords[4][1], TOL)
    }

    @Test
    fun cornerOrderAndLonLatSwap() {
        val coords = makeCoordinates()
        val expected =
            listOf(
                listOf(13.8, 49.9),
                listOf(14.2, 49.9),
                listOf(14.2, 50.1),
                listOf(13.8, 50.1),
                listOf(13.8, 49.9),
            )
        coords.zip(expected).forEach { (point, exp) ->
            assertEquals(exp[0], point[0], TOL) // longitude first
            assertEquals(exp[1], point[1], TOL) // latitude second
        }
    }
}
