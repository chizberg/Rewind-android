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
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chizberg.rewind.features.details.ui.ImageDetailsView
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.ui.LocalRewindImageLoader
import com.chizberg.rewind.features.map.ui.RewindMap
import com.chizberg.rewind.ui.OverlayScreen
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
 * plain conditional composables on top of it ([OverlayScreen] owns their animation and the system
 * back gesture). This is deliberate and was paid for once: hosting the map as the root entry of a
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
    // switch (rare) recomposes the overlay, but per-frame annotation churn does not.
    val maxRange by
        remember(graph) {
            graph.mapModel.state
                .map { it.filters.imageKind.maxRange }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(
            initialValue = graph.mapModel.state.value.filters.imageKind.maxRange,
        )

    CompositionLocalProvider(LocalRewindImageLoader provides graph.imageLoader) {
        // The map is the overlay's background: always composed (never a navigation destination, so
        // its MapView is never torn down), and scaled back by OverlayScreen behind an open details
        // screen for the system-style peek.
        OverlayScreen(
            target = appState.previewedImage,
            onBack = { graph.appModel(AppAction.ImageDetails.Dismiss) },
            modifier = modifier.fillMaxSize(),
            background = {
                RewindMap(
                    mapModel = graph.mapModel,
                    imageLoader = graph.imageLoader,
                    scheme = appState.gradientScheme,
                    focusRequests = graph.focusRequests,
                    onCardClick = { card ->
                        card.image?.let {
                            graph.appModel(AppAction.ImageDetails.Present(it, THUMBNAIL_SOURCE))
                        }
                    },
                    onAnnotationClick = { annotation ->
                        presentAnnotation(annotation) { graph.appModel(it) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            },
        ) { details ->
            ImageDetailsView(
                model = details,
                scheme = appState.gradientScheme,
                maxRange = maxRange,
                onDismiss = { graph.appModel(AppAction.ImageDetails.Dismiss) },
            )
        }

        appState.alert?.let { params ->
            AlertDialog(
                onDismissRequest = { graph.appModel(AppAction.Alert.Dismiss) },
                title = params.title?.let { { Text(it) } },
                text = params.message?.let { { Text(it) } },
                confirmButton = {
                    TextButton(onClick = { graph.appModel(AppAction.Alert.Dismiss) }) { Text("OK") }
                },
                dismissButton =
                    params.message?.let { message ->
                        {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(message)) }) {
                                Text("Copy to clipboard")
                            }
                        }
                    },
            )
        }
    }
}

/**
 * Routes a tapped image annotation to its details screen. Only images reach here — cluster taps are
 * handled by the map itself (zoom-in, see [RewindMap]); the server-cluster preview and local-cluster
 * image-list routes land with the "open cluster previews" setting (M13) and the image list (M10).
 */
private fun presentAnnotation(
    annotation: AnnotationValue,
    dispatch: (AppAction) -> Unit,
) {
    annotation.image?.let { dispatch(AppAction.ImageDetails.Present(it, THUMBNAIL_SOURCE)) }
}
