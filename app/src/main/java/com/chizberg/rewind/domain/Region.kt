package com.chizberg.rewind.domain

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
}
