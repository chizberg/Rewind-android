package com.chizberg.rewind.features.map.ui

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.MapType
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.MapType as SdkMapType

/**
 * UI-boundary conversions between the JVM-only domain geo types and the Google Maps types.
 * The domain layer stays free of com.google.* types (architecture rule); everything that
 * touches LatLng lives here, at the UI boundary.
 */
fun Coordinate.toLatLng(): LatLng = LatLng(latitude, longitude)

/**
 * The SDK's equivalent of a domain [MapType] (iOS `MapType.mkMapType`). `HYBRID`, not `SATELLITE`:
 * iOS maps `.hybrid` to `MKMapType.hybrid`, i.e. imagery *with* labels and roads.
 */
fun MapType.toSdkMapType(): SdkMapType =
    when (this) {
        MapType.Scheme -> SdkMapType.NORMAL
        MapType.Hybrid -> SdkMapType.HYBRID
    }

/** The visible map bounds as a domain [Region] (center + span) for a byBounds request. */
fun LatLngBounds.toRegion(): Region =
    Region(
        center = Coordinate(latitude = center.latitude, longitude = center.longitude),
        span =
            Span(
                latitudeDelta = northeast.latitude - southwest.latitude,
                longitudeDelta = northeast.longitude - southwest.longitude,
            ),
    )
