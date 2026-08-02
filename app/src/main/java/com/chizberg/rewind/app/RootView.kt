package com.chizberg.rewind.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chizberg.rewind.R
import com.chizberg.rewind.features.details.ui.ImageDetailsView
import com.chizberg.rewind.features.imagelist.ui.ImageListView
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.LocationAction
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.PreviewCard
import com.chizberg.rewind.features.map.ui.LocalRewindImageLoader
import com.chizberg.rewind.features.map.ui.RewindMap
import com.chizberg.rewind.features.onboarding.ui.OnboardingView
import com.chizberg.rewind.features.search.ui.SearchView
import com.chizberg.rewind.features.settings.ui.SettingsView
import com.chizberg.rewind.ui.Overlay
import com.chizberg.rewind.ui.OverlayHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** iOS `RootView.TransitionSource.thumbnail`: opening details from a preview card or a pin. */
private const val THUMBNAIL_SOURCE = "thumbnail"

/**
 * The app's root. Port of iOS `RootView`: the map with its controls, plus state-managed overlays on
 * top (image details, the image list, the place search, the settings screen, the first-run
 * onboarding, alerts). The [AppGraph] is held by [RewindViewModel], so it — and all the loaded map
 * state — survives activity recreation (rotation, or a recreate while the process lives) instead of
 * being rebuilt. The camera restores separately from saved instance state (maps-compose's
 * `rememberCameraPositionState` is `rememberSaveable`).
 *
 * [RewindMap] is composed here permanently, never as a navigation destination, and overlays are
 * declared through [OverlayHost]/[Overlay] on top of it (the host owns their animation, the receding
 * of the layer below, and the system back gesture). This is deliberate and was paid for once:
 * hosting the map as the root entry of a
 * `NavDisplay` back stack looks equivalent but is not — the default single-pane scene treats only
 * the top entry as current, so the map's `MapView` was destroyed and rebuilt on every details open.
 * That re-requested tiles, re-ran clustering and icon rasterisation, and re-played every pin's
 * entrance animation. Do not move the map into a navigation stack without first proving the entry
 * below the top survives, on a map panned AWAY from its initial region (at the initial region a
 * reset is indistinguishable from a survival).
 *
 * Only [AppState] is collected here (navigation events, rare), so per-frame map churn — which lives
 * in the map reducer's state — never recomposes this level.
 */
@Composable
fun RootView(modifier: Modifier = Modifier) {
    // Held by a ViewModel so it outlives activity recreation; its reducers run on viewModelScope.
    val graph = viewModel<RewindViewModel>().graph

    val appState by graph.appModel.state.collectAsStateWithLifecycle()
    // The year-tint range tracks the selected image kind; collected distinctly so a photo/painting
    // switch (rare) recomposes the overlay, but per-frame annotation churn does not. The seed is read
    // once inside `remember` (a non-`@Composable` lambda, so off-composition) — reading `state.value`
    // directly in composition would be an unsubscribed read (Compose lint), and this level must NOT
    // subscribe to the full map state.
    val initialMaxRange = remember(graph) { graph.mapModel.state.value.filters.imageKind.maxRange }
    val maxRange by
        remember(graph) {
            graph.mapModel.state
                .map { it.filters.imageKind.maxRange }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = initialMaxRange)

    // The system permission dialog can only be launched from composition, so the map's
    // `requestAccess` lands here and the verdict goes back into the location reducer.
    LocationPermissionHost(
        requests = graph.locationPermissionRequests,
        onAccessChanged = { granted ->
            graph.locationModel(
                LocationAction.LocationEvent.DidChangeAuthorizationStatus(granted),
            )
        },
    )

    CompositionLocalProvider(LocalRewindImageLoader provides graph.imageLoader) {
        // The map is the permanent base of the overlay stack: always composed (never a navigation
        // destination, so its MapView is never torn down), receded behind whatever opens over it.
        // Every overlay is declared through [OverlayHost]/[Overlay], so the receding of the layer
        // below (map behind list/details, list behind its own details) and the back gesture are the
        // host's job — there is no per-overlay background slot left to forget.
        OverlayHost(
            modifier = modifier,
            base = {
                RewindMap(
                    mapModel = graph.mapModel,
                    imageLoader = graph.imageLoader,
                    scheme = appState.gradientScheme,
                    focusRequests = graph.focusRequests,
                    openClusterPreviews = graph.openClusterPreviews,
                    onCardClick = { card -> presentCard(card) { graph.appModel(it) } },
                    onAnnotationClick = { annotation ->
                        presentAnnotation(annotation) { graph.appModel(it) }
                    },
                    onLocalClusterClick = { cluster ->
                        graph.appModel(
                            AppAction.ImageList.Present(cluster.images, R.string.cluster),
                        )
                    },
                    onFavoritesClick = { graph.appModel(AppAction.ImageList.PresentFavorites) },
                    onViewAsListClick = {
                        graph.appModel(AppAction.ImageList.PresentCurrentRegionImages)
                    },
                    onSearchClick = { graph.appModel(AppAction.Search.Present) },
                    onSettingsClick = { graph.appModel(AppAction.Settings.Present) },
                    // Port of iOS's `.task { if appStore.onboardingStore == nil { … } }`: while the
                    // onboarding is up the map must NOT announce itself, or the system location
                    // dialog would pop over the welcome screen — something iOS never does.
                    // Finishing the onboarding sends the action by hand instead (see
                    // `AppGraph.onFinish`).
                    onMapLoaded = {
                        if (appState.onboardingModel == null) {
                            graph.mapModel(MapAction.External.Ui.MapViewLoaded)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            },
            overlays = {
                // Pin-opened details and the list are mutually-exclusive layers over the map; each
                // recedes the map automatically. Details opened from inside the list stack one higher
                // still (declared inside [ImageListView]).
                Overlay(
                    target = appState.previewedImage,
                    onBack = { graph.appModel(AppAction.ImageDetails.Dismiss) },
                ) { details ->
                    ImageDetailsView(
                        model = details,
                        scheme = appState.gradientScheme,
                        maxRange = maxRange,
                        onDismiss = { graph.appModel(AppAction.ImageDetails.Dismiss) },
                    )
                }

                Overlay(
                    target = appState.previewedList,
                    onBack = { graph.appModel(AppAction.ImageList.Dismiss) },
                ) { listModel ->
                    ImageListView(
                        model = listModel,
                        scheme = appState.gradientScheme,
                        maxRange = maxRange,
                        onDismiss = { graph.appModel(AppAction.ImageList.Dismiss) },
                    )
                }

                // The place search is a peer of those two (iOS presents it as a sheet over the map;
                // here it is one more layer in the same stack, so back closes it the same way).
                Overlay(
                    target = appState.searchModel,
                    onBack = { graph.appModel(AppAction.Search.Dismiss) },
                ) { searchModel ->
                    SearchView(
                        model = searchModel,
                        onDismiss = { graph.appModel(AppAction.Search.Dismiss) },
                    )
                }

                Overlay(
                    target = appState.settingsModel,
                    onBack = { graph.appModel(AppAction.Settings.Dismiss) },
                ) { settingsModel ->
                    SettingsView(
                        model = settingsModel,
                        onDismiss = { graph.appModel(AppAction.Settings.Dismiss) },
                    )
                }

                // Declared last, so it is the topmost layer on a first launch. Like iOS's
                // `.fullScreenCover`, it cannot be backed out of — the only way on is the final
                // button, because a wizard the user skipped by reflex is a wizard they never saw.
                // A null `onBack` (rather than an empty one) takes the layer out of the gesture
                // altogether, so back falls through to the activity and minimises the app the way
                // the home gesture does on iOS; the wizard is still there on the way back.
                Overlay(
                    target = appState.onboardingModel,
                    onBack = null,
                ) { onboardingModel ->
                    OnboardingView(
                        model = onboardingModel,
                        scheme = appState.gradientScheme,
                    )
                }
            },
        )

        appState.alert?.let { params ->
            RewindAlert(params = params, onDismiss = { graph.appModel(AppAction.Alert.Dismiss) })
        }
    }
}

/**
 * Routes a tapped preview card: an image opens its details; the trailing "view as list" card opens
 * the current-region list; the "no images" placeholder does nothing.
 */
private fun presentCard(
    card: PreviewCard,
    dispatch: (AppAction) -> Unit,
) {
    when (card) {
        is PreviewCard.Image ->
            dispatch(AppAction.ImageDetails.Present(card.value, THUMBNAIL_SOURCE))
        PreviewCard.ViewAsList -> dispatch(AppAction.ImageList.PresentCurrentRegionImages)
        PreviewCard.NoImages -> Unit
    }
}

/**
 * Routes a tapped annotation to a details screen. An image opens itself; a server cluster opens the
 * representative image it was drawn from — but only reaches here when the "open cluster previews"
 * setting is on, since otherwise [RewindMap] zooms the map in instead. A local cluster never
 * arrives here at all (it opens the "Cluster" list through `onLocalClusterClick`).
 */
private fun presentAnnotation(
    annotation: AnnotationValue,
    dispatch: (AppAction) -> Unit,
) {
    val image =
        when (annotation) {
            is AnnotationValue.Image -> annotation.value
            is AnnotationValue.Cluster -> annotation.value.preview
            is AnnotationValue.LocalCluster -> null
        }
    image?.let { dispatch(AppAction.ImageDetails.Present(it, THUMBNAIL_SOURCE)) }
}
