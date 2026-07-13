package com.chizberg.rewind.features.map.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.domain.zoom
import com.chizberg.rewind.network.AnnotationLoadingParams
import com.chizberg.rewind.network.RequestPerformer
import com.chizberg.rewind.network.RewindRemotes
import com.chizberg.rewind.network.invoke
import com.chizberg.rewind.network.okHttpRequestPerformer
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import okhttp3.OkHttpClient

private const val TAG = "RewindMap"

// Europe and Africa — mirrors the iOS RewindMap `initialRegion`.
private val InitialRegion =
    Region(
        center = Coordinate(latitude = 15.908556, longitude = 15.796728),
        span = Span(latitudeDelta = 76.225, longitudeDelta = 76.225),
    )

// A world-view start zoom for the initial region. iOS derives this from the span via its
// screen-adjusted zoom table, which we don't port (see Zoom.kt); 3 approximates that view.
private const val INITIAL_ZOOM = 3f

/**
 * The map surface. Port of iOS `RewindMap`. M5 wires temporary scaffolding: on each camera idle it
 * fires a single byBounds fetch and drops default markers at the returned photos/clusters. This is
 * replaced in M6 by `MapModel` (debounce, cancellation, incremental clustering, tinted annotations).
 */
@Composable
fun RewindMap(modifier: Modifier = Modifier) {
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(InitialRegion.center.toLatLng(), INITIAL_ZOOM)
        }

    // M5 throwaway composition root; the real one is AppGraph in M9.
    val remotes =
        remember { RewindRemotes(RequestPerformer(okHttpRequestPerformer(OkHttpClient()))) }
    var images by remember { mutableStateOf<List<ModelImage>>(emptyList()) }
    var clusters by remember { mutableStateOf<List<ModelCluster>>(emptyList()) }

    LaunchedEffectFetch(cameraPositionState, remotes) { imgs, cls ->
        images = imgs
        clusters = cls
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
    ) {
        images.forEach { image ->
            Marker(
                state = rememberUpdatedMarkerState(position = image.coordinate.toLatLng()),
                title = image.title,
            )
        }
        clusters.forEach { cluster ->
            Marker(
                state = rememberUpdatedMarkerState(position = cluster.coordinate.toLatLng()),
                title = cluster.count.toString(),
            )
        }
    }
}

/**
 * On each camera idle (movement stopped + projection ready) fires one byBounds fetch for the
 * visible region and hands the results back. `collectLatest` cancels an in-flight fetch when a
 * newer idle arrives — M6 formalizes this as debounce + a fixed-id cancelling async effect.
 */
@Composable
private fun LaunchedEffectFetch(
    cameraPositionState: CameraPositionState,
    remotes: RewindRemotes,
    onLoaded: (List<ModelImage>, List<ModelCluster>) -> Unit,
) {
    LaunchedEffect(cameraPositionState, remotes) {
        snapshotFlow {
            if (cameraPositionState.isMoving) {
                null
            } else {
                cameraPositionState.projection?.visibleRegion?.latLngBounds
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collectLatest { bounds ->
                val region = bounds.toRegion()
                val params =
                    AnnotationLoadingParams(
                        zoom = zoom(cameraPositionState.position.zoom),
                        coordinates = region.geoJsonCoordinates,
                        startAt = System.currentTimeMillis() / 1000.0,
                        filters = ImageRequestFilters.default,
                    )
                runCatching { remotes.annotations.load(params) }
                    .onSuccess { (imgs, cls) ->
                        Log.d(
                            TAG,
                            "loaded ${imgs.size} images, ${cls.size} clusters @ z${params.zoom}",
                        )
                        onLoaded(imgs, cls)
                    }.onFailure { Log.w(TAG, "annotation load failed", it) }
            }
    }
}
