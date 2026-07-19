package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate

/**
 * A request to recenter the map camera on [coordinate] at [zoom]. The Android stand-in for iOS
 * `MapAction.External.focusOn`: our camera lives in Compose (`cameraPositionState`), not the
 * reducer, so a focus is emitted as an event the root view animates rather than a reducer effect.
 */
data class CameraFocus(
    val coordinate: Coordinate,
    val zoom: Float,
)
