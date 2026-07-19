package com.chizberg.rewind.features.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.domain.zoom
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.CameraFocus
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.MapState
import com.chizberg.rewind.features.map.PreviewCard
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

// Europe and Africa — mirrors the iOS RewindMap `initialRegion`.
private val InitialRegion =
    Region(
        center = Coordinate(latitude = 15.908556, longitude = 15.796728),
        span = Span(latitudeDelta = 76.225, longitudeDelta = 76.225),
    )

// A world-view start zoom for the initial region; the first camera idle replaces it (see Zoom.kt).
private const val INITIAL_ZOOM = 3f

// Server-cluster thumbnail render size in dp (mirrors AnnotationIconFactory's 60dp frame).
private const val SERVER_CLUSTER_DP = 60f

// First-frame guess for the strip's height, used until it reports its measured size (avoids a
// visible marker/logo jump on the first layout). Real height replaces it once the strip is laid out.
private val InitialStripHeight = 210.dp

/**
 * The map surface. Port of iOS `RewindMap`. Each camera idle hands the visible region + zoom to
 * [mapModel], which debounces, loads, clusters, and cancels in-flight loads; the map renders
 * `state.annotations` declaratively via [AnnotationOverlay] (Composables on top of the map, not SDK
 * markers, so they scale-pop in/out like iOS). Models and the shared Coil [imageLoader] are owned by
 * [com.chizberg.rewind.app.AppGraph] and injected here; tapping an annotation or a preview card is
 * routed up via [onAnnotationClick] / [onCardClick]. [focusRequests] recenters the camera (the
 * Android stand-in for iOS `focusOn`).
 */
@Composable
fun RewindMap(
    mapModel: Reducer<MapState, MapAction>,
    imageLoader: ImageLoader,
    scheme: GradientScheme,
    focusRequests: Flow<CameraFocus>,
    onCardClick: (PreviewCard) -> Unit,
    onAnnotationClick: (AnnotationValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(InitialRegion.center.toLatLng(), INITIAL_ZOOM)
        }

    val state by mapModel.state.collectAsStateWithLifecycle()
    // Materialized once per data change: `MapState.annotations` is a computed getter building a
    // fresh list on every read, and state also emits for region/isLoading-only changes — the same
    // cached instance lets the overlay skip those entirely.
    val annotations =
        remember(state.clusters, state.clusteredImages) { state.annotations }

    val localDensity = LocalDensity.current
    val density = localDensity.density
    val context = LocalContext.current
    // The strip's measured height, fed back to the map's bottom padding (keeps the Google logo above
    // it) and to the overlay (so marker placement matches the padded map center). Dynamic because it
    // includes the device's navigation-bar / home-indicator inset.
    var stripHeight by remember { mutableStateOf(InitialStripHeight) }
    // All icon rasterization and delivery machinery (Android-only, see AnnotationIconPipeline).
    val iconPipeline =
        remember(imageLoader) {
            AnnotationIconPipeline(
                icons = AnnotationIconFactory(context, density),
                imageLoader = imageLoader,
                // Cluster thumbnails render at 60dp; decode to that (in px), not full resolution.
                thumbnailTargetPx = (SERVER_CLUSTER_DP * density).toInt(),
            )
        }
    val maxRange = state.filters.imageKind.maxRange

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
                val cameraZoom = cameraPositionState.position.zoom
                mapModel(
                    MapAction.External.Map.RegionChanged(
                        region = bounds.toRegion(),
                        zoom = zoom(cameraZoom),
                        cameraZoom = cameraZoom,
                    ),
                )
            }
    }

    // "Show on map" / cluster focus: animate the camera to the requested point + zoom.
    LaunchedEffect(focusRequests, cameraPositionState) {
        focusRequests.collect { focus ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(focus.coordinate.toLatLng(), focus.zoom),
            )
        }
    }

    Box(modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            // Keep the Google logo and any attribution above the bottom preview strip. The same
            // inset is handed to the overlay so its marker placement matches the map's target.
            contentPadding = PaddingValues(bottom = stripHeight),
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
        // Annotations sit above the map surface, projected onto it (see AnnotationOverlay).
        AnnotationOverlay(
            annotations = annotations,
            region = state.region,
            cameraPositionState = cameraPositionState,
            scheme = scheme,
            maxRange = maxRange,
            iconPipeline = iconPipeline,
            onAnnotationClick = onAnnotationClick,
            contentPaddingBottom = stripHeight,
        )
        PreviewStrip(
            previews = state.previews,
            isLoading = state.isLoading,
            scheme = scheme,
            maxRange = maxRange,
            onCardClick = onCardClick,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { stripHeight = with(localDensity) { it.height.toDp() } },
        )
    }
}
