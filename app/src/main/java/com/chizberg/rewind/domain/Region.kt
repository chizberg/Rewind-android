package com.chizberg.rewind.domain

import kotlin.math.abs

/** Latitude/longitude deltas of a [Region]. Port of iOS MKCoordinateSpan. */
data class Span(
    val latitudeDelta: Double,
    val longitudeDelta: Double,
)

/**
 * A rectangular map region (center + span). Port of iOS `Region` (MKCoordinateRegion).
 * JVM-only; converted to the Google Maps types at the UI boundary.
 */
data class Region(
    val center: Coordinate,
    val span: Span,
) {
    private val geoJsonPoints: List<Coordinate>
        get() {
            val halfLat = span.latitudeDelta / 2
            val halfLon = span.longitudeDelta / 2
            return listOf(
                Coordinate(center.latitude - halfLat, center.longitude - halfLon),
                Coordinate(center.latitude - halfLat, center.longitude + halfLon),
                Coordinate(center.latitude + halfLat, center.longitude + halfLon),
                Coordinate(center.latitude + halfLat, center.longitude - halfLon),
                Coordinate(center.latitude - halfLat, center.longitude - halfLon),
            ).map { it.wrap() }
        }

    /** Closed 5-point ring, each point `[longitude, latitude]` — the order PastVu requires. */
    val geoJsonCoordinates: List<List<Double>>
        get() = geoJsonPoints.map { listOf(it.longitude, it.latitude) }

    /** This region with its span scaled ×[times] around the same center (viewport padding). */
    fun expanded(times: Double): Region =
        Region(
            center = center,
            span =
                Span(
                    latitudeDelta = (span.latitudeDelta * times).coerceAtMost(180.0),
                    longitudeDelta = (span.longitudeDelta * times).coerceAtMost(360.0),
                ),
        )

    /** Whether [coordinate] lies inside, with longitude measured across the antimeridian. */
    fun contains(coordinate: Coordinate): Boolean {
        if (abs(coordinate.latitude - center.latitude) > span.latitudeDelta / 2) return false
        // Wrapped longitude distance: |Δlon| folded into [0, 180] (the +540 keeps `%` positive).
        val lonDistance = abs((coordinate.longitude - center.longitude + 540.0) % 360.0 - 180.0)
        return lonDistance <= span.longitudeDelta / 2
    }
}
