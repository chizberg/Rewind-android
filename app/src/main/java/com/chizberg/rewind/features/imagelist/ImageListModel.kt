package com.chizberg.rewind.features.imagelist

import androidx.annotation.StringRes
import com.chizberg.rewind.app.ImageDetailsFactory
import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.sorted
import com.chizberg.rewind.features.details.ImageDetailsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** The open-source tag the list hands to its nested details screens. Port of iOS's hardcoded
 * `imageDetailsFactory(image, "image_list")` — an internal marker, never shown, never localized. */
private const val IMAGE_LIST_SOURCE = "image_list"

/** The grid-list reducer. Port of iOS `ImageListModel`. */
typealias ImageListModel = Reducer<ImageListState, ImageListAction>

/**
 * One image-grid screen. Port of iOS `ImageListState`.
 *
 * Divergences from iOS:
 * - **`title` is a string resource id**, not a `LocalizedStringKey`; the view resolves it. A plain
 *   Int keeps this reducer JVM-only.
 * - **no `matchedTransitionSourceName`**: that field only fed the shared-element zoom transition,
 *   which this port doesn't do (details open through the plain `Overlay`, no shared element).
 * - **no `Identified` wrapper** around the nested [imageDetails]: Compose keys the overlay off
 *   content presence, so a plain nullable child model suffices.
 * - **[sorting] `null`** means the sort menu is hidden entirely (the Favorites list).
 */
data class ImageListState(
    @StringRes val title: Int,
    val images: List<ModelImage>,
    val imageDetails: ImageDetailsModel? = null,
    val sorting: ImageSorting? = null,
)

sealed interface ImageListAction {
    data class PresentImage(
        val image: ModelImage,
    ) : ImageListAction

    data object DismissImage : ImageListAction

    data class UpdateImages(
        val images: List<ModelImage>,
    ) : ImageListAction

    data class SetSorting(
        val sorting: ImageSorting,
    ) : ImageListAction
}

/**
 * Builds an image-list reducer. Port of iOS `makeImageListModel`.
 *
 * [listUpdates] feeds later images in (the Favorites list is live; every other caller passes
 * `emptyFlow()`). Important: it must NOT replay its current value — iOS uses VGSL `newValues`, which
 * delivers only emissions *after* subscription. A `StateFlow` replays its current value to every new
 * collector, so the Favorites caller passes `favoritesModel.state.drop(1)`; without the `drop(1)`
 * the first (unreversed) replay would immediately clobber the reversed initial [images] snapshot.
 * `UpdateImages` deliberately does NOT re-apply the current [sorting] (mirrors iOS): the only live
 * source is Favorites, whose `sorting` is `null`, so the combination never arises in practice.
 *
 * Divergence from iOS: the **initial** [images] ARE sorted by the current [sorting] (when non-null).
 * iOS leaves them raw, which is fine for the current-region list (the map already hands it a sorted
 * array) but leaves the local-cluster list — whose `cluster.images` arrive in set/insertion order —
 * out of order under a menu that claims the current sorting is active. Re-selecting that active
 * sorting is then a no-op (the `SetSorting` equality guard), so it could never be corrected. Sorting
 * up front makes the shown order always match the checked menu item; it is idempotent for the
 * already-sorted current-region list and skipped entirely for Favorites (`sorting == null`).
 */
fun makeImageListModel(
    @StringRes title: Int,
    images: List<ModelImage>,
    listUpdates: Flow<List<ModelImage>>,
    imageDetailsFactory: ImageDetailsFactory,
    sorting: Property<ImageSorting>?,
    scope: CoroutineScope,
): ImageListModel =
    // Type args are explicit: the trailing `.adding(...)` otherwise leaves the reduce lambda's
    // parameter types un-inferable.
    Reducer<ImageListState, ImageListAction>(
        initial =
            ImageListState(
                title = title,
                images = sorting?.let { images.sorted(it.value) } ?: images,
                imageDetails = null,
                sorting = sorting?.value,
            ),
        scope = scope,
    ) { state, action, effect, _ ->
        when (action) {
            is ImageListAction.PresentImage ->
                state.copy(imageDetails = imageDetailsFactory(action.image, IMAGE_LIST_SOURCE))

            ImageListAction.DismissImage -> state.copy(imageDetails = null)

            is ImageListAction.UpdateImages -> state.copy(images = action.images)

            is ImageListAction.SetSorting ->
                if (state.sorting == action.sorting) {
                    state
                } else {
                    // Persist only when a sorting Property was supplied (Favorites has none).
                    effect { sorting?.value = action.sorting }
                    state.copy(
                        sorting = action.sorting,
                        images = state.images.sorted(action.sorting),
                    )
                }
        }
    }.adding(listUpdates) { ImageListAction.UpdateImages(it) }
