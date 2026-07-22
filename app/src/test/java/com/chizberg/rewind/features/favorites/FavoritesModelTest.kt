@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.features.favorites

import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageDate
import com.chizberg.rewind.domain.ModelImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirror of iOS `FavoritesModelTests`. `FavoritesModel` dedups add/remove by `ModelImage`'s
 * cid-only equality (`ModelImage.equals`/`hashCode` compare only `cid`, ignoring every other
 * field — see `domain/ModelImage.kt`). These tests pin that behavior with two DISTINCT instances
 * sharing a cid but differing in every other field, so a port that widened equality to the full
 * class (or compared some other key) would fail: it would either grow a duplicate entry on
 * re-favoriting the same photo from a different source screen, or fail to find/remove the stored
 * entry when the caller only has a partially-populated instance (e.g. one rehydrated from
 * persisted `StorageImage`, which carries fewer fields than a freshly-fetched `ModelImage`).
 */
class FavoritesModelTest {
    /**
     * Adding an image whose cid already exists in favorites is a no-op: the original entry is
     * kept (not replaced), and the synchronous storage effect is not re-invoked for the
     * duplicate.
     */
    @Test
    fun addToFavoritesDedupesByCidKeepingTheFirstEntry() =
        reducerTest { scope ->
            val storage = StorageSpy()
            val model = makeFavoritesModel(storage.property, scope)

            val first = makeImage(cid = 1, title = "A", imagePath = "a.jpg", year = 1900)
            val sameCidDifferentEverythingElse =
                makeImage(cid = 1, title = "B", imagePath = "b.jpg", year = 1950)

            model(FavoritesAction.AddToFavorites(first))
            model(FavoritesAction.AddToFavorites(sameCidDifferentEverythingElse))

            assertEquals(1, model.state.value.size)
            assertEquals(
                "A",
                model.state.value
                    .first()
                    .title,
            )
            assertEquals(
                "a.jpg",
                model.state.value
                    .first()
                    .imagePath,
            )

            // The second dispatch hit the dedup guard and returned before enqueuing the
            // persistence effect at all -- not just "persisted the same thing twice".
            assertEquals(1, storage.writes.size)
            assertEquals(listOf("A"), storage.writes.last().map { it.title })
        }

    /**
     * Removing an image finds the stored entry by cid alone: an instance with the same cid but
     * different title/imagePath/coordinate still matches and is removed; an unrelated cid is a
     * no-op that neither changes state nor re-invokes the storage effect.
     */
    @Test
    fun removeFromFavoritesMatchesStoredEntryByCidAlone() =
        reducerTest { scope ->
            val toRemove = makeImage(cid = 1, title = "A", imagePath = "a.jpg", year = 1900)
            val toKeep = makeImage(cid = 2, title = "B", imagePath = "b.jpg", year = 1950)
            val storage = StorageSpy(initial = listOf(toRemove, toKeep))
            val model = makeFavoritesModel(storage.property, scope)

            val differentInstanceSameCid =
                makeImage(cid = 1, title = "Z", imagePath = "z.jpg", year = 2020)
                    .copy(coordinate = Coordinate(latitude = 10.0, longitude = 20.0))
            model(FavoritesAction.RemoveFromFavorites(differentInstanceSameCid))

            assertEquals(1, model.state.value.size)
            assertEquals(
                2,
                model.state.value
                    .first()
                    .cid,
            )
            assertEquals(
                "B",
                model.state.value
                    .first()
                    .title,
            )
            assertEquals(1, storage.writes.size)
            assertEquals(listOf("B"), storage.writes.last().map { it.title })

            // An unrelated cid finds no index -- state and storage are both left untouched, not
            // just "unchanged after another identical write".
            model(
                FavoritesAction.RemoveFromFavorites(
                    makeImage(cid = 99, title = "Nope", imagePath = "nope.jpg", year = 1),
                ),
            )
            assertEquals(1, model.state.value.size)
            assertEquals(1, storage.writes.size)
        }
}

// MARK: - Fixtures

private fun makeImage(
    cid: Int,
    title: String,
    imagePath: String,
    year: Int,
): ModelImage =
    ModelImage(
        cid = cid,
        imagePath = imagePath,
        title = title,
        dir = null,
        coordinate = Coordinate(latitude = 0.0, longitude = 0.0),
        date = ImageDate(year = year, year2 = year),
    )

private class StorageSpy(
    initial: List<ModelImage> = emptyList(),
) {
    val writes = mutableListOf<List<ModelImage>>()
    private var backing: List<ModelImage> = initial

    val property: Property<List<ModelImage>> =
        Property(
            getter = { backing },
            setter = { newValue ->
                backing = newValue
                writes += newValue
            },
        )
}

/** Gives the reducer a scope on the test scheduler (not `backgroundScope`, which `advanceUntilIdle`
 * ignores); cancelled afterwards so pending effects don't leak. Mirrors `ImageDetailsModelTest`. */
private fun reducerTest(body: suspend TestScope.(scope: CoroutineScope) -> Unit) =
    runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }
