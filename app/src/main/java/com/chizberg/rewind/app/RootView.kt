package com.chizberg.rewind.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chizberg.rewind.R
import com.chizberg.rewind.features.details.ui.ImageDetailsView
import com.chizberg.rewind.features.imagelist.ui.ImageListView
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.PreviewCard
import com.chizberg.rewind.features.map.ui.LocalRewindImageLoader
import com.chizberg.rewind.features.map.ui.RewindMap
import com.chizberg.rewind.ui.Overlay
import com.chizberg.rewind.ui.OverlayHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** iOS `RootView.TransitionSource.thumbnail`: opening details from a preview card or a pin. */
private const val THUMBNAIL_SOURCE = "thumbnail"

/**
 * The app's root. Port of iOS `RootView`: the map with its controls, plus state-managed overlays on
 * top (image details, alerts; the list/search/settings/onboarding overlays land in their
 * milestones). The [AppGraph] is held by [RewindViewModel], so it — and all the loaded map state —
 * survives activity recreation (rotation, or a recreate while the process lives) instead of being
 * rebuilt. The camera restores separately from saved instance state (maps-compose's
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
    val clipboard = LocalClipboardManager.current
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
            },
        )

        appState.alert?.let { params ->
            AlertDialog(
                onDismissRequest = { graph.appModel(AppAction.Alert.Dismiss) },
                title = params.title?.let { { Text(it) } },
                text = params.message?.let { { Text(it) } },
                confirmButton = {
                    TextButton(onClick = { graph.appModel(AppAction.Alert.Dismiss) }) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton =
                    params.message?.let { message ->
                        {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(message)) }) {
                                Text(stringResource(R.string.copy_to_clipboard))
                            }
                        }
                    },
            )
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
 * Routes a tapped image annotation to its details screen. Only images reach here — a server cluster
 * zooms the map in ([RewindMap]) and a local cluster opens the "Cluster" list (`onLocalClusterClick`);
 * the server-cluster preview route lands with the "open cluster previews" setting (M13).
 */
private fun presentAnnotation(
    annotation: AnnotationValue,
    dispatch: (AppAction) -> Unit,
) {
    annotation.image?.let { dispatch(AppAction.ImageDetails.Present(it, THUMBNAIL_SOURCE)) }
}
