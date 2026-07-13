package com.chizberg.rewind.features.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState

// Europe and Africa — mirrors the iOS RewindMap `initialRegion`.
private val InitialRegion =
    Region(
        center = Coordinate(latitude = 15.908556, longitude = 15.796728),
        span = Span(latitudeDelta = 76.225, longitudeDelta = 76.225),
    )

// span -> zoom conversion is M5 (Zoom.kt); 3 approximates the iOS initial view for now.
private const val INITIAL_ZOOM = 3f

/**
 * The map surface. Port of iOS `RewindMap`. M4 shows the bare interactive world map at the
 * iOS initial region; annotations, region-change events and clustering arrive in M5/M6.
 */
@Composable
fun RewindMap(modifier: Modifier = Modifier) {
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(InitialRegion.center.toLatLng(), INITIAL_ZOOM)
        }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        // Mirror iOS RewindMapView: rotation and pitch (tilt) disabled; no Android-only zoom
        // buttons or map toolbar (iOS MapKit has no such controls).
        uiSettings =
            MapUiSettings(
                rotationGesturesEnabled = false,
                tiltGesturesEnabled = false,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
            ),
    )
}
