@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.features.search

import com.chizberg.rewind.domain.Coordinate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirror of the (nonexistent, iOS has no equivalent unit test — `SearchSuggestProvider` is built
 * inline in `makeSearchModel` there and is untestable) Android-only test approved for M12: tapping
 * a specific suggest resolves *that* suggest's place, not the first/last one in the list, and hands
 * exactly one coordinate to the injected [onLocationFound] callback with no alert raised.
 *
 * Only the input action -> external effect is asserted (per the approved contract): the internal
 * `Resolved`/`ResolveFailed` actions are not inspected directly.
 */
class SearchModelTest {
    @Test
    fun suggestSelectedResolvesTappedPlace() =
        reducerTest { scope ->
            // Two distinct, real-world places so a wrong-index resolve is unmistakable.
            val eiffel =
                SearchState.Suggest(
                    placeId = "eiffel",
                    title = "Eiffel Tower",
                    subtitle = "Paris, France",
                )
            val eiffelCoordinate = Coordinate(latitude = 48.8584, longitude = 2.2945)
            val louvre =
                SearchState.Suggest(
                    placeId = "louvre",
                    title = "Louvre Museum",
                    subtitle = "Paris, France",
                )
            val louvreCoordinate = Coordinate(latitude = 48.8606, longitude = 2.3376)

            val provider =
                FakePlacesSuggestProvider(
                    resolutions =
                        mapOf(
                            eiffel.placeId to eiffelCoordinate,
                            louvre.placeId to louvreCoordinate,
                        ),
                )
            val foundLocations = mutableListOf<Coordinate>()
            val model =
                makeSearchModel(
                    suggestProvider = provider,
                    onLocationFound = { foundLocations += it },
                    scope = scope,
                )

            // Tap the SECOND suggest in the list — must resolve louvre, not eiffel.
            model(SearchAction.External.SuggestSelected(louvre))
            advanceUntilIdle()

            assertEquals(listOf(louvreCoordinate), foundLocations)
            assertEquals(listOf(louvre.placeId), provider.resolvedIds)
            assertNull(model.state.value.alert)
        }

    /**
     * Tapping a second suggest while the first is still resolving: only the second place is handed
     * over, and the first one's late answer is dropped rather than yanking the camera back.
     *
     * This pins an Android-only fix, not iOS behaviour: there every lookup gets a fresh random
     * effect id, so the reply that happens to land last wins (see the M12 divergence note). The fake
     * holds the first resolve open until after the second has already been delivered, then releases
     * it — a naive port without the stable effect id calls back twice, the second time with the
     * place the user did not tap last.
     */
    @Test
    fun secondSuggestSelectionSupersedesTheFirst() =
        reducerTest { scope ->
            val eiffel =
                SearchState.Suggest(
                    placeId = "eiffel",
                    title = "Eiffel Tower",
                    subtitle = "Paris, France",
                )
            val louvre =
                SearchState.Suggest(
                    placeId = "louvre",
                    title = "Louvre Museum",
                    subtitle = "Paris, France",
                )
            val louvreCoordinate = Coordinate(latitude = 48.8606, longitude = 2.3376)

            val slowEiffel = CompletableDeferred<Unit>()
            val provider =
                FakePlacesSuggestProvider(
                    resolutions =
                        mapOf(
                            eiffel.placeId to Coordinate(latitude = 48.8584, longitude = 2.2945),
                            louvre.placeId to louvreCoordinate,
                        ),
                    gates = mapOf(eiffel.placeId to slowEiffel),
                )
            val foundLocations = mutableListOf<Coordinate>()
            val model =
                makeSearchModel(
                    suggestProvider = provider,
                    onLocationFound = { foundLocations += it },
                    scope = scope,
                )

            model(SearchAction.External.SuggestSelected(eiffel))
            advanceUntilIdle()
            model(SearchAction.External.SuggestSelected(louvre))
            advanceUntilIdle()

            // The first lookup answers only now, after the second one has already been delivered.
            slowEiffel.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(louvreCoordinate), foundLocations)
            // Both lookups really started — otherwise there would be no race to supersede.
            assertEquals(listOf(eiffel.placeId, louvre.placeId), provider.resolvedIds)
            assertNull(model.state.value.alert)
        }
}

/**
 * A fake [PlacesSuggestProvider] resolving each suggest's `placeId` via a hand-written table. A
 * `placeId` listed in [gates] stays unresolved until the test completes its deferred, which is how
 * a slow lookup is held open across another one.
 */
private class FakePlacesSuggestProvider(
    private val resolutions: Map<String, Coordinate>,
    private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
) : PlacesSuggestProvider {
    val resolvedIds = mutableListOf<String>()

    override suspend fun suggestions(query: String): List<SearchState.Suggest> = emptyList()

    override suspend fun resolve(suggest: SearchState.Suggest): Coordinate {
        resolvedIds += suggest.placeId
        gates[suggest.placeId]?.await()
        return resolutions.getValue(suggest.placeId)
    }
}

/**
 * Gives the reducer a scope on the test scheduler (not `backgroundScope`, which `advanceUntilIdle`
 * ignores); cancelled afterwards so pending effects don't leak. Mirrors the helper in
 * MapModelTest/ImageDetailsModelTest.
 */
private fun reducerTest(body: suspend TestScope.(scope: CoroutineScope) -> Unit) =
    runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }
