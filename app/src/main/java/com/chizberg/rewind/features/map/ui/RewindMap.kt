package com.chizberg.rewind.features.map.ui

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ModelLocalCluster
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.domain.zoom
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.CameraFocus
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.MapState
import com.chizberg.rewind.features.map.PreviewCard
import com.chizberg.rewind.features.map.coordinate
import com.chizberg.rewind.features.map.key
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

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

// Side (dp) of the invisible tap-target marker for pins and local clusters — the 48dp Material touch
// target, since the visible pin (20×26) is too small to tap. Server clusters use their own 60dp icon
// size instead, so a tap anywhere on the visible thumbnail lands. Identity is still resolved exactly
// by the SDK (bounds + z-order); this only sets how large the tappable area is.
private const val TAP_TARGET_DP = 48f

// How much a cluster tap zooms in, centred on the cluster (iOS MapModel: `currentZoom + 1`).
private const val CLUSTER_ZOOM_STEP = 1f

// First-frame guess for the strip's height, used until it reports its measured size (avoids a
// visible marker/logo jump on the first layout). Real height replaces it once the strip is laid out.
private val InitialStripHeight = 210.dp

// Gap between the floating filter toolbar and the preview strip below it.
private val FiltersGap = 8.dp

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
    onLocalClusterClick: (ModelLocalCluster) -> Unit,
    onFavoritesClick: () -> Unit,
    onViewAsListClick: () -> Unit,
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

    val cameraScope = rememberCoroutineScope()
    // What a tap on an annotation does. An image opens its details (routed up to [onAnnotationClick]);
    // a local cluster opens its images as the "Cluster" grid list (routed up to [onLocalClusterClick],
    // the iOS default for a local cluster); a server cluster zooms the camera in toward it — the iOS
    // default when the "open cluster previews" setting is off (that opt-in setting lands in M13).
    val onAnnotationTapped: (AnnotationValue) -> Unit = { annotation ->
        when (annotation) {
            is AnnotationValue.Image -> onAnnotationClick(annotation)
            is AnnotationValue.LocalCluster -> onLocalClusterClick(annotation.value)
            is AnnotationValue.Cluster ->
                cameraScope.launch {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(
                            annotation.coordinate().toLatLng(),
                            cameraPositionState.position.zoom + CLUSTER_ZOOM_STEP,
                        ),
                    )
                }
        }
    }

    Box(modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            // Base map tiles follow the system theme, like MapKit on iOS. Driven off the same
            // `isSystemInDarkTheme()` signal as the Compose theme (rather than the SDK's own
            // FOLLOW_SYSTEM) so both switch on the exact same recomposition.
            mapColorScheme =
                if (isSystemInDarkTheme()) {
                    ComposeMapColorScheme.DARK
                } else {
                    ComposeMapColorScheme.LIGHT
                },
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
        ) {
            // Invisible SDK markers, one per annotation, are the tap targets: the SDK hit-tests them
            // natively (exact bounds + z-order, no guessing) and never blocks a map drag, so a swipe
            // begun on a marker pans while a tap selects. The visible, animated icons stay in the
            // Compose overlay above; these carry no pixels, only clicks.
            AnnotationHitTargets(
                annotations = annotations,
                density = density,
                onAnnotationClick = onAnnotationTapped,
            )
        }
        // Annotations sit above the map surface, projected onto it (see AnnotationOverlay).
        AnnotationOverlay(
            annotations = annotations,
            region = state.region,
            cameraPositionState = cameraPositionState,
            scheme = scheme,
            maxRange = maxRange,
            iconPipeline = iconPipeline,
            contentPaddingBottom = stripHeight,
        )
        // The floating filter toolbar sits directly above the preview strip (port of the iOS
        // FloatingMenu row over the bottom card); both are pinned to the bottom edge as one column.
        // The strip still reports only its own height back for the map padding + marker placement.
        // Leading-aligned, like iOS `MapControls`' `VStack(alignment: .leading)`: once the toolbar
        // stops filling the width (landscape, where it caps out) it hugs the side rather than
        // floating in the middle of the map.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            FiltersControl(
                filters = state.filters,
                scheme = scheme,
                expandedItems = state.controls.expandedItems,
                onFiltersChanged = { mapModel(MapAction.External.Ui.FiltersChanged(it)) },
                onExpandedItemsChanged = {
                    mapModel(
                        MapAction.External.Ui.Controls
                            .SetExpandedItems(it),
                    )
                },
                // Side insets stay inside FiltersControl (they belong to the animated node there —
                // see its expansion comment); only the gap above the strip is set here.
                modifier =
                    Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                        ).padding(vertical = FiltersGap),
            )
            PreviewStrip(
                previews = state.previews,
                isLoading = state.isLoading,
                scheme = scheme,
                maxRange = maxRange,
                onCardClick = onCardClick,
                onFavoritesClick = onFavoritesClick,
                onViewAsListClick = onViewAsListClick,
                modifier =
                    Modifier.onSizeChanged {
                        stripHeight = with(localDensity) { it.height.toDp() }
                    },
            )
        }
    }
}

/**
 * One transparent SDK marker per annotation, positioned at its coordinate — the map's native,
 * exact tap targets. The SDK owns hit-testing (bounds + z-order) and fires [onAnnotationClick] on a
 * tap; a drag begun on a marker is never claimed, so the map still pans. The markers carry no
 * pixels — the visible, animated icons live in the Compose overlay above (see [AnnotationOverlay]).
 *
 * Kept off the file's iOS-parallel path deliberately: this is Android infrastructure for the
 * native-selection divergence, sitting inside the [GoogleMap] content where BitmapDescriptors are
 * safe to build (the SDK is initialised by the time this composes).
 */
@Composable
@GoogleMapComposable
private fun AnnotationHitTargets(
    annotations: List<AnnotationValue>,
    density: Float,
    onAnnotationClick: (AnnotationValue) -> Unit,
) {
    // One shared descriptor per size: the 60dp server-cluster thumbnail, and a 48dp touch target
    // for the smaller pins and local clusters. Anchored centre to match the overlay's placement.
    val clusterIcon = remember(density) { transparentIcon((SERVER_CLUSTER_DP * density).toInt()) }
    val pinIcon = remember(density) { transparentIcon((TAP_TARGET_DP * density).toInt()) }
    annotations.forEach { annotation ->
        key(annotation.key()) {
            Marker(
                state = rememberUpdatedMarkerState(annotation.coordinate().toLatLng()),
                icon = if (annotation is AnnotationValue.Cluster) clusterIcon else pinIcon,
                anchor = Offset(0.5f, 0.5f),
                onClick = {
                    onAnnotationClick(annotation)
                    true
                },
            )
        }
    }
}

/** A fully transparent square [BitmapDescriptor] of [sizePx] — an invisible marker with real bounds. */
private fun transparentIcon(sizePx: Int): BitmapDescriptor {
    val side = sizePx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
