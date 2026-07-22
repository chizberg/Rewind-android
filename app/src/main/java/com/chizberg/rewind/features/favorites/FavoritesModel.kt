package com.chizberg.rewind.features.favorites

import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.core.redux.ViewStore
import com.chizberg.rewind.core.redux.viewStore
import com.chizberg.rewind.domain.ModelImage
import kotlinx.coroutines.CoroutineScope

/**
 * Port of iOS `Favorites/FavoritesModel.swift`.
 *
 * `Reducer<List<ModelImage>, FavoritesAction>`. Add/remove are idempotent by [ModelImage]'s
 * cid-only `equals`/`hashCode` (see `domain/ModelImage.kt`) — `state.contains(image)` /
 * `state.indexOf(image)` already resolve by cid because of that override, exactly like iOS's
 * `Model.Image.==`. Persistence runs as a SYNCHRONOUS `effect` (not `asyncEffect`): iOS's
 * `Property` setter is a plain synchronous call (`UserDefaults` underneath), and the Android
 * `Property<List<ModelImage>>` passed in as [storage] must present the same synchronous shape —
 * any DataStore asynchrony is hidden inside the concrete `JsonPreference`-backed `Property` the
 * caller supplies, not surfaced here as an `asyncEffect` with an id/debounce.
 */
sealed interface FavoritesAction {
    data class AddToFavorites(
        val image: ModelImage,
    ) : FavoritesAction

    data class RemoveFromFavorites(
        val image: ModelImage,
    ) : FavoritesAction
}

typealias FavoritesModel = Reducer<List<ModelImage>, FavoritesAction>

/**
 * iOS `makeFavoritesModel(storage:)`: initial state is [storage]'s current value (so callers that
 * feed it back into a follow-up screen — e.g. `presentFavorites` — see what was persisted before
 * this model started running).
 */
fun makeFavoritesModel(
    storage: Property<List<ModelImage>>,
    scope: CoroutineScope,
): FavoritesModel =
    Reducer(storage.value, scope) { state, action, effect, _ ->
        when (action) {
            is FavoritesAction.AddToFavorites ->
                // `contains` resolves by cid (ModelImage equality is cid-only): re-favoriting the
                // same photo from a different screen is a no-op — no duplicate, no second write.
                if (action.image in state) {
                    state
                } else {
                    val next = state + action.image
                    effect { storage.value = next }
                    next
                }

            is FavoritesAction.RemoveFromFavorites -> {
                // `indexOf` also resolves by cid, so a partially-populated instance (e.g. one
                // rehydrated from a persisted StorageImage) still finds and removes the stored entry.
                val index = state.indexOf(action.image)
                if (index < 0) {
                    state
                } else {
                    val next = state.toMutableList().apply { removeAt(index) }
                    effect { storage.value = next }
                    next
                }
            }
        }
    }

/**
 * iOS `FavoritesModel.isFavorite(_:)`: a `ViewStore<Bool, Bool>` projecting whether [image] is in
 * favorites (cid membership) and lifting a toggle back into add/remove. Recreating it per call is
 * harmless — `bimap` keeps no state of its own.
 */
fun FavoritesModel.isFavorite(image: ModelImage): ViewStore<Boolean, Boolean> =
    viewStore.bimap(
        state = { list -> list.any { it.cid == image.cid } },
        action = { fav ->
            if (fav) {
                FavoritesAction.AddToFavorites(image)
            } else {
                FavoritesAction.RemoveFromFavorites(image)
            }
        },
    )
