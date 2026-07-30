package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate

/**
 * A request to recenter the map camera on [coordinate] at [zoom]. The Android stand-in for iOS
 * `MapAction.External.focusOn`: our camera lives in Compose (`cameraPositionState`), not the
 * reducer, so a focus is emitted as an event the root view animates rather than a reducer effect.
 *
 * [animated] is iOS's `map.value.set(region:animated:)` flag. Every deliberate move — "show on map",
 * a search result, the location button — flies; only the one-off recenter on the first location fix
 * cuts straight there (iOS `newLocationState` passes `animated: false`), since it happens on its own
 * while the user is looking at the map, and a world-to-city flight would read as the map bolting.
 */
data class CameraFocus(
    val coordinate: Coordinate,
    val zoom: Float,
    val animated: Boolean = true,
)
