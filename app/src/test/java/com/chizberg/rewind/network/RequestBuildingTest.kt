package com.chizberg.rewind.network

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

// A real region's ring (closed 5-point [lon, lat] loop) — exactly what the app feeds byBounds.
private val RING =
    Region(
        center = Coordinate(latitude = 50.08, longitude = 14.42),
        span = Span(latitudeDelta = 0.03, longitudeDelta = 0.07),
    ).geoJsonCoordinates

/**
 * Mirror of iOS RequestBuildingTests. Inspects the getByBounds `params` by DECODING the query
 * item (order-independent). The one thing a naive port gets silently wrong is the extra array
 * level that nests the ring into a valid GeoJSON Polygon; forget it and the server returns nothing.
 */
class RequestBuildingTest {
    private fun byBoundsParams(): JsonObject {
        val request =
            Request
                .byBounds(
                    zoom = 13,
                    coordinates = RING,
                    startAt = 0.0,
                    yearRange = 1826..2000,
                    isPainting = false,
                ).makeRequest()
        val params = request.url.queryParameter("params")!!
        return Json.parseToJsonElement(params).jsonObject
    }

    @Test
    fun byBoundsWrapsRingInPolygonCoordinates() {
        // Polygon coordinates are an array of linear rings: the ring must be nested one level
        // deeper. Forget the wrap and the polygon is malformed; the server returns nothing.
        val coordinates = byBoundsParams()["geometry"]!!.jsonObject["coordinates"]!!
        val rings = Json.decodeFromJsonElement<List<List<List<Double>>>>(coordinates)
        assertEquals(1, rings.size)
        assertEquals(RING, rings[0])
    }
}
