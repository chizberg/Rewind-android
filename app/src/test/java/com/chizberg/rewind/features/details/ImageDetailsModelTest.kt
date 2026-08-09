@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.features.details

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageDate
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of iOS `ImageDetailsModelTests`: the description-link routing. A link to a pastvu photo
 * (`https://pastvu.com/p/<cid>`) recurses into a nested details screen loaded from the remote; any
 * other link (external host, or a non-photo pastvu path) is handed to the url opener. The routing
 * hinges on a branchy URL parse (host + `/p/` + integer cid) — worth pinning — while the raw
 * button/url string tables are not tested.
 */
class ImageDetailsModelTest {
    /**
     * A link to a pastvu photo loads that photo's details and presents them as a nested details
     * screen; `loadingAnotherImage` flips true synchronously while the load is in flight, and the
     * url opener is never called.
     */
    @Test
    fun pastvuPhotoLinkOpensNestedDetails() =
        reducerTest { scope ->
            val harness = Harness(scope)
            val model = harness.makeModel()

            val linkedCid = 2_223_969
            model(ImageDetailsAction.DescriptionLink("https://pastvu.com/p/$linkedCid"))
            assertTrue(model.state.value.loadingAnotherImage) // set synchronously in reduce

            advanceUntilIdle()

            assertNotNull(model.state.value.anotherImageModel)
            // loadingAnotherImage clears once the child screen is presented
            assertFalse(model.state.value.loadingAnotherImage)
            assertEquals(listOf(linkedCid), harness.requestedCids)
            assertTrue(harness.openedUrls.isEmpty()) // recursion, not a browser hand-off
        }

    /** A link to an external host opens in the browser instead of recursing. */
    @Test
    fun externalLinkOpensInBrowser() =
        reducerTest { scope ->
            val harness = Harness(scope)
            val model = harness.makeModel()

            val external = "https://example.com/gallery"
            model(ImageDetailsAction.DescriptionLink(external))
            advanceUntilIdle()

            assertEquals(listOf(external), harness.openedUrls)
            assertFalse(model.state.value.loadingAnotherImage)
            assertNull(model.state.value.anotherImageModel)
            assertTrue(harness.requestedCids.isEmpty())
        }

    /** A pastvu link that isn't a photo page (a user profile) opens in the browser, not mistaken
     * for a photo to recurse into. */
    @Test
    fun nonPhotoPastvuLinkOpensInBrowser() =
        reducerTest { scope ->
            val harness = Harness(scope)
            val model = harness.makeModel()

            val userPage = "https://pastvu.com/u/someone"
            model(ImageDetailsAction.DescriptionLink(userPage))
            advanceUntilIdle()

            assertEquals(listOf(userPage), harness.openedUrls)
            assertNull(model.state.value.anotherImageModel)
            assertTrue(harness.requestedCids.isEmpty())
        }

    /**
     * Mirror of iOS `repeatedTranslateAfterShowingOriginalReusesCachedResult`.
     *
     * A successful translation is cached for the life of the screen: returning to the original
     * text and translating again reuses the cached result synchronously, without a second round
     * trip to the remote — translate only calls the network once per screen no matter how many
     * times the user toggles translate/original.
     */
    @Test
    fun repeatedTranslateAfterShowingOriginalReusesCachedResult() =
        reducerTest { scope ->
            val imageTitle = "Vulica Kastryčnickaja"
            val description = "Вулица Кастрычницкая"
            val harness = Harness(scope)
            var translateCallCount = 0
            val model =
                harness.makeModel(
                    imageTitle = imageTitle,
                    detailsDescription = description,
                    translate =
                        Remote { params ->
                            translateCallCount++
                            "[xx] " + params.text
                        },
                )

            // Seed state.details (and its description) the way the screen normally would before
            // translate is ever offered.
            model(ImageDetailsAction.WillBePresented)
            advanceUntilIdle()

            model(ImageDetailsAction.Translate)
            // flips synchronously, before the network responds
            assertEquals(TranslationState.Translating, model.state.value.translationState)

            advanceUntilIdle()

            val firstTranslationState = model.state.value.translationState
            check(firstTranslationState is TranslationState.Translated) {
                "expected Translated after the first translate, was $firstTranslationState"
            }
            val firstTranslation = firstTranslationState.translation
            assertEquals(2, translateCallCount) // title + description, requested concurrently
            assertEquals("[xx] $imageTitle", firstTranslation.title)
            assertEquals("[xx] $description", firstTranslation.description)

            model(ImageDetailsAction.ShowTranslationOriginal)
            assertEquals(TranslationState.Available, model.state.value.translationState)

            model(ImageDetailsAction.Translate)
            // cache hit: synchronous, no scheduler pump needed to observe the result
            assertEquals(
                TranslationState.Translated(firstTranslation),
                model.state.value.translationState,
            )
            assertEquals(2, translateCallCount) // no new network calls
        }
}

private class Harness(
    private val scope: CoroutineScope,
) {
    val openedUrls = mutableListOf<String>()
    val requestedCids = mutableListOf<Int>()

    /**
     * [imageTitle]/[detailsDescription] default to the pre-M15 fixtures (empty title, no
     * description) so the three link-routing tests above are unaffected; the M15 translate test
     * supplies its own distinguishable, non-empty strings so a title/description slot swap in the
     * translation result is unmistakable. [translate] stands where the iOS Harness keeps its
     * `.mock("translated")` default, but poisoned: a test that dispatches `.Translate` is expected
     * to supply its own stub, and an accidental call should fail loudly rather than pass through a
     * value nobody chose.
     */
    fun makeModel(
        imageTitle: String = "",
        detailsDescription: String? = null,
        translate: Remote<TranslateParams, String> =
            Remote { TODO("this test dispatched .Translate without supplying a translate stub") },
    ): ImageDetailsModel =
        makeImageDetailsModel(
            modelImage = mockImage(cid = 1, title = imageTitle),
            remote =
                Remote { cid ->
                    requestedCids += cid
                    mockDetails(cid, description = detailsDescription)
                },
            openSource = "",
            isFavorite = { false },
            setFavorite = { _, _ -> },
            showOnMap = {},
            canOpenUrl = { true },
            urlOpener = { openedUrls += it },
            saveImage = {},
            shareImage = {},
            translate = translate,
            extractModelImage = { details -> mockImage(cid = details.cid) },
            scope = scope,
        )
}

private fun mockImage(
    cid: Int,
    title: String = "",
): ModelImage =
    ModelImage(
        cid = cid,
        imagePath = "",
        title = title,
        dir = null,
        coordinate = Coordinate(latitude = 0.0, longitude = 0.0),
        date = ImageDate(year = 1900, year2 = 1900),
    )

private fun mockDetails(
    cid: Int,
    description: String? = null,
): ModelImageDetails =
    ModelImageDetails(
        cid = cid,
        title = "",
        direction = null,
        coordinate = Coordinate(latitude = 0.0, longitude = 0.0),
        date = ImageDate(year = 1900, year2 = 1900),
        description = description,
        source = null,
        address = null,
        author = null,
        username = "",
        file = "",
        dir = null,
    )

/** Gives the reducer a scope on the test scheduler (not `backgroundScope`, which `advanceUntilIdle`
 * ignores); cancelled afterwards so pending effects don't leak. Mirrors the MapModelTest helper. */
private fun reducerTest(body: suspend TestScope.(scope: CoroutineScope) -> Unit) =
    runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }
