package com.chizberg.rewind.app

import androidx.annotation.StringRes
import com.chizberg.rewind.R
import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.details.ImageDetailsModel
import com.chizberg.rewind.features.favorites.FavoritesModel
import com.chizberg.rewind.features.imagelist.ImageListModel
import com.chizberg.rewind.features.imagelist.makeImageListModel
import com.chizberg.rewind.features.onboarding.OnboardingModel
import com.chizberg.rewind.features.search.SearchModel
import com.chizberg.rewind.features.settings.SettingsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow

/** The app-level navigation reducer. Port of iOS `AppModel`. */
typealias AppModel = Reducer<AppState, AppAction>

/**
 * State-managed overlays over the map. Port of iOS `AppState`: the image-details screen, the image
 * list, the settings screen, the first-run onboarding, the place search, an app-level alert, and
 * the active tint scheme.
 *
 * Divergence: no `Identified` wrapper — Compose keys overlays off content presence, so a nullable
 * child model is enough. [previewedImage] and [previewedList] are siblings, as on iOS: details
 * opened straight from a pin live in [previewedImage]; details opened from inside a list live in the
 * list's own `ImageListState.imageDetails`, so clearing [previewedList] tears the whole list subtree
 * (its nested details included) down at once.
 *
 * [onboardingModel] is the odd one: it is seeded non-null at construction (and only then) when the
 * "already shown" flag says so — iOS does the same, `makeOnboardingViewModel` returning nil being
 * the gate — so it has no `present` action, only a dismiss.
 */
data class AppState(
    val gradientScheme: GradientScheme,
    val previewedImage: ImageDetailsModel? = null,
    val previewedList: ImageListModel? = null,
    val settingsModel: SettingsModel? = null,
    val onboardingModel: OnboardingModel? = null,
    val searchModel: SearchModel? = null,
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

    /**
     * The image-list overlay. Port of iOS `AppAction.ImageList`. iOS threads a `source` string on
     * each present (the shared-element transition id); this port has no shared-element transition,
     * so that field is dropped. [Present] carries a resolved title resource for the generic case
     * (the local-cluster "Cluster" list); the other two presents pick their own fixed titles.
     */
    sealed interface ImageList : AppAction {
        data class Present(
            val images: List<ModelImage>,
            @StringRes val title: Int,
        ) : ImageList

        data object PresentFavorites : ImageList

        data object PresentCurrentRegionImages : ImageList

        data object Dismiss : ImageList
    }

    /**
     * The place-search overlay. Port of iOS `AppAction.Search`: [Present] builds a *fresh* model
     * every time (the typed query and its suggests do not survive a close), [Dismiss] drops it.
     */
    sealed interface Search : AppAction {
        data object Present : Search

        data object Dismiss : Search
    }

    /**
     * The settings overlay. Port of iOS `AppAction.Settings`: like search, [Present] builds a fresh
     * model, which is how the screen always opens on the currently persisted values.
     */
    sealed interface Settings : AppAction {
        data object Present : Settings

        data object Dismiss : Settings
    }

    /** The onboarding overlay. Port of iOS `AppAction.Onboarding`: dismiss only (see [AppState]). */
    sealed interface Onboarding : AppAction {
        data object Dismiss : Onboarding
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

/** Builds a [SearchModel] for one opening of the search screen. Port of iOS `searchModelFactory`. */
typealias SearchModelFactory = () -> SearchModel

/** Builds a [SettingsModel] for one opening of the settings screen (iOS's own factory param). */
typealias SettingsModelFactory = () -> SettingsModel

/**
 * Builds the app reducer. Port of iOS `makeAppModel`.
 *
 * Divergences:
 * - iOS `imageDetails(.dismiss)` also fires `performMapAction(.previewClosed)` to deselect the
 *   tapped MapKit annotation. Our annotations aren't a MapKit selection (a tap is a transient
 *   gesture, no selection state to clear), so dismiss only closes the overlay and pokes
 *   [requestReview] — the store-review counter (`ReviewPrompter`). `imageList(.dismiss)` likewise
 *   only clears the list (its `previewClosed` is beside the point too).
 * - [favoritesModel] feeds the live Favorites list; [currentRegionImages] snapshots the visible
 *   region for the "On the map" list (both mirror iOS's `favoritesModel` / `currentRegionImages`).
 *
 * [onboardingModel] is passed in already built — or null when the flag says it has been seen (iOS
 * hands `makeAppModel` the same optional) — and simply seeds the initial state.
 */
@Suppress("LongParameterList")
fun makeAppModel(
    imageDetailsFactory: ImageDetailsFactory,
    searchModelFactory: SearchModelFactory,
    settingsModelFactory: SettingsModelFactory,
    favoritesModel: FavoritesModel,
    onboardingModel: OnboardingModel?,
    currentRegionImages: () -> List<ModelImage>,
    sorting: Property<ImageSorting>,
    requestReview: () -> Unit,
    initialGradientScheme: GradientScheme,
    scope: CoroutineScope,
): AppModel =
    Reducer(
        initial =
            AppState(
                gradientScheme = initialGradientScheme,
                onboardingModel = onboardingModel,
            ),
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

            is AppAction.ImageList.Present ->
                state.copy(
                    previewedList =
                        makeImageListModel(
                            title = action.title,
                            images = action.images,
                            listUpdates = emptyFlow(),
                            imageDetailsFactory = imageDetailsFactory,
                            sorting = sorting,
                            scope = scope,
                        ),
                )

            AppAction.ImageList.PresentFavorites ->
                state.copy(
                    previewedList =
                        makeImageListModel(
                            title = R.string.list_favorites,
                            images = favoritesModel.state.value.reversed(), // new -> old
                            // `drop(1)`: skip the StateFlow's replay of its current value (which
                            // would arrive unreversed and clobber the snapshot above); deliver only
                            // later changes. Mirrors iOS `favoritesModel.$state.newValues`.
                            listUpdates = favoritesModel.state.drop(1),
                            imageDetailsFactory = imageDetailsFactory,
                            sorting = null,
                            scope = scope,
                        ),
                )

            AppAction.ImageList.PresentCurrentRegionImages ->
                state.copy(
                    previewedList =
                        makeImageListModel(
                            title = R.string.list_on_the_map,
                            images = currentRegionImages(),
                            listUpdates = emptyFlow(),
                            imageDetailsFactory = imageDetailsFactory,
                            sorting = sorting,
                            scope = scope,
                        ),
                )

            AppAction.ImageList.Dismiss -> state.copy(previewedList = null)

            AppAction.Search.Present -> state.copy(searchModel = searchModelFactory())

            AppAction.Search.Dismiss -> state.copy(searchModel = null)

            AppAction.Settings.Present -> state.copy(settingsModel = settingsModelFactory())

            AppAction.Settings.Dismiss -> state.copy(settingsModel = null)

            AppAction.Onboarding.Dismiss -> state.copy(onboardingModel = null)

            is AppAction.Alert.Present -> action.params?.let { state.copy(alert = it) } ?: state

            AppAction.Alert.Dismiss -> state.copy(alert = null)

            is AppAction.SetGradientScheme -> state.copy(gradientScheme = action.scheme)
        }
    }
