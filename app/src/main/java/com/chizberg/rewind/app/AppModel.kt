package com.chizberg.rewind.app

import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.details.ImageDetailsModel
import kotlinx.coroutines.CoroutineScope

/** The app-level navigation reducer. Port of iOS `AppModel`. */
typealias AppModel = Reducer<AppState, AppAction>

/**
 * State-managed overlays over the map. Port of iOS `AppState`, trimmed to M9: the image-details
 * screen, an app-level alert, and the active tint scheme. The image list (M10), search (M12),
 * settings and onboarding (M13) overlays join in their milestones — same shape (a nullable child
 * model each) as they land.
 *
 * Divergence: no `Identified` wrapper — Compose keys overlays off content presence, so a nullable
 * child model is enough.
 */
data class AppState(
    val gradientScheme: GradientScheme,
    val previewedImage: ImageDetailsModel? = null,
    val alert: AlertParams? = null,
)

sealed interface AppAction {
    sealed interface ImageDetails : AppAction {
        data class Present(
            val image: ModelImage,
            val source: String,
        ) : ImageDetails

        data object Dismiss : ImageDetails
    }

    sealed interface Alert : AppAction {
        data class Present(
            val params: AlertParams?,
        ) : Alert

        data object Dismiss : Alert
    }

    data class SetGradientScheme(
        val scheme: GradientScheme,
    ) : AppAction
}

/** Builds an [ImageDetailsModel] for a tapped image. Port of iOS `ImageDetailsFactory`. */
typealias ImageDetailsFactory = (ModelImage, String) -> ImageDetailsModel

/**
 * Builds the app reducer. Port of iOS `makeAppModel`, trimmed to M9.
 *
 * Divergence: iOS `imageDetails(.dismiss)` also fires `performMapAction(.previewClosed)` to
 * deselect the tapped MapKit annotation. Our annotations aren't a MapKit selection (a tap is a
 * transient gesture, no selection state to clear), so dismiss only closes the overlay and pokes
 * [requestReview] — the App Store review counter (real prompter lands in M16).
 */
fun makeAppModel(
    imageDetailsFactory: ImageDetailsFactory,
    requestReview: () -> Unit,
    initialGradientScheme: GradientScheme,
    scope: CoroutineScope,
): AppModel =
    Reducer(
        initial = AppState(gradientScheme = initialGradientScheme),
        scope = scope,
    ) { state, action, effect, _ ->
        when (action) {
            is AppAction.ImageDetails.Present ->
                state.copy(
                    previewedImage = imageDetailsFactory(action.image, action.source),
                )

            AppAction.ImageDetails.Dismiss -> {
                effect { requestReview() }
                state.copy(previewedImage = null)
            }

            is AppAction.Alert.Present -> action.params?.let { state.copy(alert = it) } ?: state

            AppAction.Alert.Dismiss -> state.copy(alert = null)

            is AppAction.SetGradientScheme -> state.copy(gradientScheme = action.scheme)
        }
    }
