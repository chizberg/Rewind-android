package com.chizberg.rewind.features.map.ui

import com.chizberg.rewind.domain.Coordinate
import com.google.android.gms.maps.model.LatLng

/**
 * UI-boundary conversions between the JVM-only domain geo types and the Google Maps types.
 * The domain layer stays free of com.google.* types (architecture rule); everything that
 * touches LatLng lives here, at the UI boundary.
 */
fun Coordinate.toLatLng(): LatLng = LatLng(latitude, longitude)
