package com.chizberg.rewind.features.map.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
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
import com.chizberg.rewind.features.map.Minimization
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
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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

// How much of the strip stays on screen once the controls fold away (iOS `glimpseHeight`).
private val Glimpse = 50.dp

// The folded strip's dimming and shrink — iOS `content.opacity(0.5)` and
// `scaleEffect(0.9, anchor: .top)` on the card. The menu row above only travels; it keeps its size.
private const val MINIMIZED_ALPHA = 0.5f
private const val MINIMIZED_SCALE = 0.9f

// Thresholds of the strip's own drag, 1:1 with iOS `MapControlsHiding`: dragging down past 60% of
// the fold distance folds it, as does a fling predicted to land twice that distance away; dragging
// back up past 30% restores it, as does a fling predicted to end above the strip's top edge.
private const val MINIMIZE_COEFFICIENT = 0.6f
private const val MINIMIZE_ACCELERATION_COEFFICIENT = 2f
private const val MAXIMIZE_COEFFICIENT = 0.3f

/**
 * The map surface. Port of iOS `RewindMap`. Each camera idle hands the visible region + zoom to
 * [mapModel], which debounces, loads, clusters, and cancels in-flight loads; the map renders
 * `state.annotations` declaratively via [AnnotationOverlay] (Composables on top of the map, not SDK
 * markers, so they scale-pop in/out like iOS). Models and the shared Coil [imageLoader] are owned by
 * [com.chizberg.rewind.app.AppGraph] and injected here; tapping an annotation or a preview card is
 * routed up via [onAnnotationClick] / [onCardClick]. [focusRequests] recenters the camera (the
 * Android stand-in for iOS `focusOn`).
 *
 * [onMapLoaded] is passed in rather than dispatched here because iOS gates it: its equivalent lives
 * in `RootView` and is skipped entirely while the onboarding is up, so nothing asks for location
 * access over the welcome screen (see `RootView`).
 */
@Composable
fun RewindMap(
    mapModel: Reducer<MapState, MapAction>,
    imageLoader: ImageLoader,
    scheme: GradientScheme,
    focusRequests: Flow<CameraFocus>,
    openClusterPreviews: () -> Boolean,
    onCardClick: (PreviewCard) -> Unit,
    onAnnotationClick: (AnnotationValue) -> Unit,
    onLocalClusterClick: (ModelLocalCluster) -> Unit,
    onFavoritesClick: () -> Unit,
    onViewAsListClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMapLoaded: () -> Unit,
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
    // The whole controls column — menu row plus strip — which is what the map is inset by (see
    // [mapBottomPadding]). Measured, like the strip: the menu grows when the year selector unfolds.
    var controlsHeight by remember { mutableStateOf(InitialStripHeight) }
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
            // `position` is read for its subscription, not its value: `projection` is a plain
            // getter over the map, not snapshot state, so this block re-runs only when something
            // observable changes. `isMoving` alone is not enough — a programmatic `move()` (the
            // first location fix, "show on map", search) flips it true and back inside one frame,
            // the flow never re-reads, and the reducer keeps loading photos for wherever the map
            // used to be. `position` is what the map writes on every camera idle.
            cameraPositionState.position
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

    // "Show on map" / search / location focus: move the camera to the requested point + zoom,
    // flying there or cutting straight to it as the request asks (iOS `set(region:animated:)`).
    LaunchedEffect(focusRequests, cameraPositionState) {
        focusRequests.collect { focus ->
            val update =
                CameraUpdateFactory.newLatLngZoom(focus.coordinate.toLatLng(), focus.zoom)
            if (focus.animated) {
                cameraPositionState.animate(update)
            } else {
                cameraPositionState.move(update)
            }
        }
    }

    val cameraScope = rememberCoroutineScope()
    // What a tap on an annotation does. An image opens its details (routed up to
    // [onAnnotationClick]); a local cluster opens its images as the "Cluster" grid list (routed up
    // to [onLocalClusterClick], the iOS default for a local cluster); a server cluster either opens
    // its representative image or zooms the camera in toward it, exactly as iOS's
    // `annotationSelected` branches on `settings.value.openClusterPreviews`. The setting is read at
    // tap time, not observed — iOS reads it synchronously off the settings variable in the same
    // place.
    val onAnnotationTapped: (AnnotationValue) -> Unit = { annotation ->
        when (annotation) {
            is AnnotationValue.Image -> onAnnotationClick(annotation)
            is AnnotationValue.LocalCluster -> onLocalClusterClick(annotation.value)
            is AnnotationValue.Cluster ->
                if (openClusterPreviews()) {
                    onAnnotationClick(annotation)
                } else {
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
    }

    // How far the controls travel when folded away (iOS `glassCardHeight - glimpseHeight`). Ours
    // follows the measured strip, since its height is not a constant — it carries the navigation
    // bar's inset and grows when the year selector unfolds.
    val minimizedOffset = (stripHeight - Glimpse).coerceAtLeast(0.dp)
    val minimizedOffsetPx = with(localDensity) { minimizedOffset.toPx() }
    val isMinimized = state.controls.minimization.isMinimized
    // The platform's own fling projection, standing in for UIKit's `predictedEndLocation`.
    val decay = remember(localDensity) { splineBasedDecay<Float>(localDensity) }
    // How far down the controls currently sit — ONE value, as iOS keeps one `offset`, rather than a
    // spring plus a separate live drag: summing those two makes the release jump, since the drag
    // part resets to zero a frame before the spring has anything to animate from.
    val foldOffset = remember { Animatable(0f) }
    val foldScope = rememberCoroutineScope()
    var draggingStrip by remember { mutableStateOf(false) }
    // Every fold the finger did not perform (the map drag, its 2s unfold, a tap on the folded strip)
    // springs from wherever the controls are to where the state says they belong.
    LaunchedEffect(isMinimized, minimizedOffsetPx) {
        if (!draggingStrip) {
            foldOffset.animateTo(if (isMinimized) minimizedOffsetPx else 0f, PanelSpring)
        }
    }
    val stripAlpha by animateFloatAsState(
        targetValue = if (isMinimized) MINIMIZED_ALPHA else 1f,
        animationSpec = PanelSpring,
        label = "stripAlpha",
    )
    val stripScale by animateFloatAsState(
        targetValue = if (isMinimized) MINIMIZED_SCALE else 1f,
        animationSpec = PanelSpring,
        label = "stripScale",
    )
    // The map's bottom padding — the whole controls stack, as iOS insets by `controls.size.height`
    // (its VStack of the menu row *and* the card), not by the card alone. That is what puts the
    // Google logo above the floating menu instead of behind it.
    //
    // It follows the fold on the controls' own spring, so the logo travels with them (iOS animates
    // the same inset through `updateBottomInset` -> `UIView.animate(mapControlsAnimation)`), and the
    // annotation overlay is handed this very same value.
    //
    // Sharing it is not a nicety, it is the whole correctness argument. `GoogleMap.setPadding` is
    // not MapKit's `layoutMargins`: setting it re-places the SDK's own controls but does not
    // re-render the camera (measured: the base map moves 0 px), while the *next* camera change does
    // honour it. So a value the overlay does not share tears the markers off the map on the next
    // zoom, and a value the map has not yet applied tears them off during the fold. Hence the nudge
    // below: each new padding is pushed into the SDK the same frame, and map, markers and logo move
    // as one.
    val insetOffset by animateFloatAsState(
        targetValue = if (isMinimized) minimizedOffsetPx else 0f,
        animationSpec = PanelSpring,
        label = "mapBottomInset",
    )
    val mapBottomPadding = controlsHeight - with(localDensity) { insetOffset.toDp() }

    // Re-aim the camera at its own position whenever the inset moves: that is what makes the SDK
    // apply the fresh padding to what is on screen, instead of sitting on the last frame it drew.
    // The target LatLng never changes — only where the SDK draws it — so this recentres the map the
    // way MapKit's animated `layoutMargins` does, and the region change it produces is the honest
    // one (the visible area really did grow).
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { insetOffset }
            .drop(1)
            .collect {
                cameraPositionState.move(
                    CameraUpdateFactory.newCameraPosition(cameraPositionState.position),
                )
            }
    }

    Box(modifier.fillMaxSize()) {
        GoogleMap(
            // Watch the pan without taking it, iOS's `shouldRecognizeSimultaneouslyWith: true` on a
            // recognizer added to the map view itself. The node sits in the map's own modifier
            // chain, outside its view-interop node: it sees every event in the Initial pass and
            // consumes none, so the map still pans. (The repo's rule against `pointerInput` over the
            // map still holds — that is about a *sibling* above it running a consuming drag
            // detector, which wins hit-testing outright and starves the map.) Touches on the
            // controls never arrive here at all, since they are siblings drawn above the map, which
            // is exactly the isolation iOS gets from attaching the recognizer to `MKMapView`.
            modifier =
                Modifier.fillMaxSize().pointerInput(mapModel) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.pressed }
                            if (event.type == PointerEventType.Move && change != null) {
                                mapModel(
                                    MapAction.External.Map.UserDragged(
                                        touchY =
                                            change.position.y
                                                .toDp()
                                                .value,
                                        viewportHeight = size.height.toDp().value,
                                    ),
                                )
                            }
                        }
                    }
                },
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
            contentPadding = PaddingValues(bottom = mapBottomPadding),
            // The blue dot (iOS `showsUserLocation = true`). Strictly tied to the granted flag:
            // switching it on without the permission throws SecurityException. The base style comes
            // straight off the state (iOS pokes the live map view with `applyMapType` instead).
            properties =
                MapProperties(
                    isMyLocationEnabled = state.locationState.isAccessGranted,
                    mapType = state.mapType.toSdkMapType(),
                ),
            // Mirror iOS RewindMapView: rotation and pitch (tilt) disabled; no Android-only zoom
            // buttons or map toolbar (iOS MapKit has no such controls). The SDK's own my-location
            // button is off as well — ours lives in the floating menu, like iOS's.
            uiSettings =
                MapUiSettings(
                    myLocationButtonEnabled = false,
                    rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false,
                    zoomControlsEnabled = false,
                    mapToolbarEnabled = false,
                ),
            // iOS `mapViewDidFinishLoadingMap` -> `.ui(.mapViewLoaded)`: the cue to ask for
            // location access and start tracking. Gated by the caller (see the KDoc).
            onMapLoaded = onMapLoaded,
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
            contentPaddingBottom = mapBottomPadding,
        )
        // The floating menu sits directly above the preview strip (port of the iOS FloatingMenu row
        // over the bottom card); both are pinned to the bottom edge as one column. The strip still
        // reports only its own height back for the map padding + marker placement.
        // Leading-aligned, like iOS `MapControls`' `VStack(alignment: .leading)`: once the menu
        // stops filling the width (landscape, where it caps out) it hugs the side rather than
        // floating in the middle of the map.
        Column(
            // Menu and strip travel together when folded away, as iOS puts the same `offset(y:)` on
            // both halves of its `VStack`.
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged {
                        controlsHeight = with(localDensity) { it.height.toDp() }
                    }.graphicsLayer { translationY = foldOffset.value },
            horizontalAlignment = Alignment.Start,
        ) {
            FloatingMenu(
                filters = state.filters,
                scheme = scheme,
                mapType = state.mapType,
                expandedItems = state.controls.expandedItems,
                onFiltersChanged = { mapModel(MapAction.External.Ui.FiltersChanged(it)) },
                onMapTypeChanged = { mapModel(MapAction.External.Ui.MapTypeSelected(it)) },
                onExpandedItemsChanged = {
                    mapModel(
                        MapAction.External.Ui.Controls
                            .SetExpandedItems(it),
                    )
                },
                // iOS routes the search glyph to `AppAction.search(.present)`, not to the map
                // reducer — so does this one, through the root view.
                onSearchClick = onSearchClick,
                // The location glyph, unlike search, is the map reducer's own business (iOS
                // `.locationTap` -> `.right(.locationButtonTapped)`).
                locationAccessGranted = state.locationState.isAccessGranted,
                onLocationClick = {
                    mapModel(MapAction.External.Ui.LocationButtonTapped)
                },
                // Side insets stay inside FloatingMenu (they belong to the animated node there —
                // see its expansion comment); only the gap above the strip is set here.
                modifier =
                    Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                        ).padding(vertical = FiltersGap),
            )
            Box(
                // The strip's own fold gesture — port of iOS `MapControlsHiding`, installed by the
                // parent `MapControls` there and by the parent here for the same reason: the state
                // it decides belongs to the controls as a whole, not to the strip's own view.
                // Vertical only, and the cards' `LazyRow` inside is offered every event first (a
                // descendant is, in the main pass), so it claims horizontal scrolls before this ever
                // sees them — which is the job iOS gives `scrollDisabled(isVerticalDrag)`.
                modifier =
                    Modifier.pointerInput(mapModel) {
                        val velocity = VelocityTracker()
                        var startY = 0f
                        var dragTotal = 0f
                        var base = 0f
                        detectVerticalDragGestures(
                            onDragStart = { position ->
                                startY = position.y
                                dragTotal = 0f
                                base = foldOffset.value
                                draggingStrip = true
                                velocity.resetTracking()
                                foldScope.launch { foldOffset.stop() }
                            },
                            onVerticalDrag = { change, delta ->
                                dragTotal += delta
                                velocity.addPointerInputChange(change)
                                foldScope.launch { foldOffset.snapTo(base + dragTotal) }
                            },
                            // Let go without deciding anything and the controls spring back to
                            // wherever the state has them (iOS's `@GestureState` unwinds the same).
                            onDragCancel = {
                                draggingStrip = false
                                foldScope.launch {
                                    foldOffset.animateTo(
                                        if (isMinimized) minimizedOffsetPx else 0f,
                                        PanelSpring,
                                    )
                                }
                            },
                            onDragEnd = {
                                val fold = (stripHeight - Glimpse).coerceAtLeast(0.dp).toPx()
                                // iOS weighs a fling by `predictedEndLocation` — a *position*
                                // within the card, not a translation — so the prediction here is
                                // also a position, and the thresholds keep its scale.
                                val predictedEnd =
                                    decay.calculateTargetValue(
                                        startY + dragTotal,
                                        velocity.calculateVelocity().y,
                                    )
                                val folded = state.controls.minimization.isMinimized
                                val target =
                                    when {
                                        !folded &&
                                            (
                                                dragTotal >= fold * MINIMIZE_COEFFICIENT ||
                                                    predictedEnd >=
                                                    fold * MINIMIZE_ACCELERATION_COEFFICIENT
                                            ) -> Minimization.Minimized(byUser = true)

                                        folded &&
                                            (
                                                dragTotal <= -fold * MAXIMIZE_COEFFICIENT ||
                                                    predictedEnd <= -fold
                                            ) -> Minimization.Normal

                                        else -> null
                                    }
                                // Settle from where the finger left the controls, not from where
                                // the state was a moment ago — the spring's start is the last
                                // dragged position.
                                val settlesFolded =
                                    (target ?: state.controls.minimization)
                                        .isMinimized
                                draggingStrip = false
                                foldScope.launch {
                                    foldOffset.animateTo(
                                        if (settlesFolded) fold else 0f,
                                        PanelSpring,
                                    )
                                }
                                target?.let {
                                    mapModel(
                                        MapAction.External.Ui.Controls
                                            .SetMinimization(it),
                                    )
                                }
                            },
                        )
                    },
            ) {
                PreviewStrip(
                    previews = state.previews,
                    isLoading = state.isLoading,
                    scheme = scheme,
                    maxRange = maxRange,
                    onCardClick = onCardClick,
                    onFavoritesClick = onFavoritesClick,
                    onViewAsListClick = onViewAsListClick,
                    onSettingsClick = onSettingsClick,
                    modifier =
                        Modifier
                            .onSizeChanged {
                                val height = with(localDensity) { it.height.toDp() }
                                stripHeight = height
                                // The band a map drag has to reach to fold the controls. iOS
                                // declares it as a constant; ours is measured (iOS `sizeChanged`).
                                mapModel(
                                    MapAction.External.Ui.Controls
                                        .SizeChanged(height.value),
                                )
                            }.graphicsLayer {
                                scaleX = stripScale
                                scaleY = stripScale
                                // Anchored at the top edge, iOS `scaleEffect(anchor: .top)`.
                                transformOrigin =
                                    TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)
                            },
                    contentAlpha = { stripAlpha },
                )
                if (isMinimized) {
                    // A tap on the folded strip brings it back, and until then it swallows taps so
                    // the dimmed cards underneath cannot be hit (iOS's clear overlay on the card).
                    // It covers the strip only — the menu row above stays live, as it does there.
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                mapModel(
                                    MapAction.External.Ui.Controls
                                        .SetMinimization(Minimization.Normal),
                                )
                            },
                    )
                }
            }
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
