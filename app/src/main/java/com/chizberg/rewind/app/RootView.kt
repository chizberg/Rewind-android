package com.chizberg.rewind.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.features.details.ui.ImageDetailsView
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.ui.LocalRewindImageLoader
import com.chizberg.rewind.features.map.ui.RewindMap
import com.chizberg.rewind.ui.OverlayScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** iOS `RootView.TransitionSource.thumbnail`: opening details from a preview card or a pin. */
private const val THUMBNAIL_SOURCE = "thumbnail"

/**
 * The app's root. Port of iOS `RootView`: the map with its controls, plus state-managed overlays on
 * top (image details, alerts; the list/search/settings/onboarding overlays land in their
 * milestones). Owns the [AppGraph] for the composition's lifetime — a rotation-surviving
 * holder-ViewModel is a later refinement (the map already re-inits on rotation today).
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
    val context = LocalContext.current
    // A single-threaded main scope for every reducer (the @MainActor equivalent).
    val scope = remember { CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    val graph = remember { AppGraph(context, scope) }

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
 * Routes a tapped annotation to a details screen. An image → its details; a server cluster → its
 * preview's details (iOS's "cluster previews" mode; the zoom-in / image-list routing lands with M10).
 */
private fun presentAnnotation(
    annotation: AnnotationValue,
    dispatch: (AppAction) -> Unit,
) {
    val image =
        when (annotation) {
            is AnnotationValue.Image -> annotation.value
            is AnnotationValue.Cluster -> annotation.value.preview
            is AnnotationValue.LocalCluster -> null // image list lands in M10
        }
    image?.let { dispatch(AppAction.ImageDetails.Present(it, THUMBNAIL_SOURCE)) }
}
