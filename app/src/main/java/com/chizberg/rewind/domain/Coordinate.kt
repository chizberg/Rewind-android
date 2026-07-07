package com.chizberg.rewind.domain

import kotlin.math.abs
import kotlin.math.floor

/**
 * JVM-only geographic coordinate. The domain layer stays free of android.* /
 * com.google.* types (own [Coordinate]/[Region]); conversion to LatLng happens only
 * at the UI boundary. Port of iOS `Coordinate` (typealias for CLLocationCoordinate2D)
 * in MapKit+Extensions.swift.
 */
data class Coordinate(
    val latitude: Double,
    val longitude: Double,
) {
    fun reversed(): Coordinate = Coordinate(latitude = longitude, longitude = latitude)

    /**
     * Antimeridian / pole normalization. Line-for-line port of iOS `wrap()`.
     * https://gist.github.com/missinglink/d0a085188a8eab2ca66db385bb7c023a
     */
    fun wrap(): Coordinate {
        val quadrant = (abs(latitude) / 90).toInt() % 4
        val pole = if (latitude > 0) 90.0 else -90.0
        val offset = latitude % 90

        var lat = latitude
        var lon = longitude
        when (quadrant) {
            0 -> lat = offset
            1 -> {
                lat = pole - offset
                lon += 180
            }
            2 -> {
                lat = -offset
                lon += 180
            }
            3 -> lat = -pole + offset
        }

        if (lon > 180 || lon < -180) {
            lon -= floor((lon + 180) / 360) * 360
        }
        return Coordinate(latitude = lat, longitude = lon)
    }

    companion object {
        val zero = Coordinate(0.0, 0.0)

        /** PastVu geo arrays are `[latitude, longitude]`. Mirrors iOS `Coordinate(_ arr:)`. */
        fun fromArray(arr: List<Double>): Coordinate {
            if (arr.size != 2) return zero
            return Coordinate(latitude = arr[0], longitude = arr[1])
        }
    }
}
