package com.chizberg.rewind.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.domain.ModelImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.ListSerializer

/** iOS `UserDefaults` key the favorites list is stored under; kept identical for parity. */
private const val FAVORITES_KEY = "favorites"

/**
 * The favorites persistence gateway. Port of iOS `FavoritesStorage`.
 *
 * It exposes a synchronous [property] of live [ModelImage]s (what `FavoritesModel` mutates) over a
 * persisted [JsonPreference] of [StorageImage] DTOs: reads come from an in-memory [modelImages]
 * cache seeded at construction from the persisted list, and each write updates that cache and
 * re-encodes the DTOs. The iOS class additionally threads a `makeLoadableImage` to rebuild lazy
 * image loaders on rehydrate; ours carries only the path, so there is nothing extra to rebuild.
 *
 * The DTO layer (`StorageImage`) is deliberately separate from `ModelImage` so the on-disk shape
 * can evolve independently, exactly as on iOS.
 */
class FavoritesStorage(
    dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val impl =
        JsonPreference(
            dataStore = dataStore,
            key = FAVORITES_KEY,
            serializer = ListSerializer(StorageImage.serializer()),
            defaultValue = emptyList(),
            scope = scope,
        )

    private var modelImages: List<ModelImage> = impl.value.map { it.toModelImage() }

    val property: Property<List<ModelImage>> =
        Property(
            getter = { modelImages },
            setter = { newValue ->
                modelImages = newValue
                impl.value = newValue.map { it.toStorageImage() }
            },
        )
}
