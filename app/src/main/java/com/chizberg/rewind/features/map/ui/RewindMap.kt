package com.chizberg.rewind.features.map.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.domain.zoom
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.makeMapModel
import com.chizberg.rewind.network.RequestPerformer
import com.chizberg.rewind.network.RewindRemotes
import com.chizberg.rewind.network.invoke
import com.chizberg.rewind.network.okHttpRequestPerformer
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

// A world-view start zoom for the initial region; the first camera idle replaces it (see Zoom.kt).
private const val INITIAL_ZOOM = 3f

/**
 * The map surface. Port of iOS `RewindMap`. Each camera idle hands the visible region + zoom to
 * [makeMapModel], which debounces, loads, clusters, and cancels in-flight loads; the map then
 * renders `state.annotations` declaratively. Divergence from iOS: no imperative annotation add/
 * remove — the render is a pure function of state, so a same-zoom pan accumulates pins
 * incrementally without churn (M6 core). Composition root is throwaway; AppGraph takes over in M9.
 */
@Composable
fun RewindMap(modifier: Modifier = Modifier) {
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(InitialRegion.center.toLatLng(), INITIAL_ZOOM)
        }

    // The reducer needs a single-threaded main scope; cancel it on leaving composition.
    val mapScope = remember { CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()) }
    DisposableEffect(Unit) { onDispose { mapScope.cancel() } }
    val mapModel =
        remember {
            val remotes = RewindRemotes(RequestPerformer(okHttpRequestPerformer(OkHttpClient())))
            makeMapModel(
                annotationsRemote = remotes.annotations,
                onLoadFailed = { Log.w(TAG, "annotation load failed", it) },
                scope = mapScope,
            )
        }
    val state by mapModel.state.collectAsStateWithLifecycle()

    // Camera idle (movement stopped + projection ready) → feed the region + zoom to the reducer.
    LaunchedEffect(mapModel, cameraPositionState) {
        snapshotFlow {
            if (cameraPositionState.isMoving) {
                null
            } else {
                cameraPositionState.projection?.visibleRegion?.latLngBounds
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { bounds ->
                mapModel(
                    MapAction.External.Map.RegionChanged(
                        region = bounds.toRegion(),
                        zoom = zoom(cameraPositionState.position.zoom),
                    ),
                )
            }
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
        state.annotations.forEach { annotation ->
            key(annotation.renderKey()) {
                Marker(
                    state = rememberUpdatedMarkerState(position = annotation.coordinate.toLatLng()),
                    title = annotation.title,
                )
            }
        }
    }
}

// M6 uses default markers; tinted/rotated pins, cluster thumbnails and counts arrive in M7.
private val AnnotationValue.coordinate: Coordinate
    get() =
        when (this) {
            is AnnotationValue.Image -> value.coordinate
            is AnnotationValue.Cluster -> value.coordinate
            is AnnotationValue.LocalCluster -> value.coordinate
        }

private val AnnotationValue.title: String
    get() =
        when (this) {
            is AnnotationValue.Image -> value.title
            is AnnotationValue.Cluster -> value.count.toString()
            is AnnotationValue.LocalCluster -> value.images.size.toString()
        }

/** Stable identity so a marker survives recomposition (image cid / local-cluster id). */
private fun AnnotationValue.renderKey(): String =
    when (this) {
        is AnnotationValue.Image -> "i:${value.cid}"
        is AnnotationValue.Cluster ->
            "c:${value.preview.cid}@${value.coordinate.latitude},${value.coordinate.longitude}"
        is AnnotationValue.LocalCluster -> "l:${value.id}"
    }
