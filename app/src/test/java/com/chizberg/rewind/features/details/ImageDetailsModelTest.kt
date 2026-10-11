@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.features.details

import com.chizberg.rewind.colorization.BlockingModel
import com.chizberg.rewind.colorization.ColorizationCheck
import com.chizberg.rewind.colorization.ColorizationModel
import com.chizberg.rewind.colorization.ColorlessModel
import com.chizberg.rewind.colorization.WatermarkedImage
import com.chizberg.rewind.colorization.grayImage
import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.tinyPhoto
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Mirror of iOS `ImageDetailsModelTests`. Description-link routing: a link to a pastvu photo
 * (`https://pastvu.com/p/<cid>`) recurses into a nested details screen loaded from the remote; any
 * other link (external host, or a non-photo pastvu path) is handed to the url opener. The routing
 * hinges on a branchy URL parse (host + `/p/` + integer cid) — worth pinning. And details seeding:
 * details already known when the model is built land in the initial state instead of arriving as a
 * later update. The raw button/url string tables are not tested.
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
     * Mirror of iOS `knownDetailsSeedInitialState`. Details known upfront are part of the very
     * first state, and presenting the screen doesn't ask for them again. Android-specific stake:
     * the translation verdict is the detector's round trip here (not part of `apply` as on iOS), so
     * a seeded screen must still run it on presentation — or the translate chip never shows.
     */
    @Test
    fun knownDetailsSeedInitialState() =
        reducerTest { scope ->
            val harness = Harness(scope)
            val details = mockDetails(cid = 1, description = "Вулица Кастрычницкая")
            val foreign =
                LanguageDetector { DetectedLanguage(languageCode = "be", confidence = 1f) }
            val seeded = harness.makeModel(cachedDetails = details, detectLanguage = foreign)
            val loaded = harness.makeModel(detectLanguage = foreign)

            assertEquals(details, seeded.state.value.details) // before anything is dispatched

            seeded(ImageDetailsAction.WillBePresented)
            loaded(ImageDetailsAction.Internal.DetailsLoaded(details))
            advanceUntilIdle()

            assertEquals(TranslationState.Available, seeded.state.value.translationState)
            assertEquals(loaded.state.value.translationState, seeded.state.value.translationState)
            assertTrue(harness.requestedCids.isEmpty()) // details were not refetched
        }

    /** Mirror of iOS `unknownDetailsAreLoadedOnPresentation`: without known details, presentation
     * loads them. */
    @Test
    fun unknownDetailsAreLoadedOnPresentation() =
        reducerTest { scope ->
            val harness = Harness(scope)
            val model = harness.makeModel()

            assertNull(model.state.value.details)
            model(ImageDetailsAction.WillBePresented)
            advanceUntilIdle()

            assertNotNull(model.state.value.details)
            assertEquals(listOf(1), harness.requestedCids)
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

    /** A recreated activity presents the same screen again; iOS's `.willBePresented` does
     *  nothing once the details are in, so the translation on screen stays. */
    @Test
    fun presentingAgainKeepsTheTranslation() =
        reducerTest { scope ->
            val model =
                Harness(scope).makeModel(
                    detailsDescription = "Вулица Кастрычницкая",
                    translate = Remote { params -> "[xx] " + params.text },
                )
            model(ImageDetailsAction.WillBePresented)
            advanceUntilIdle()
            model(ImageDetailsAction.Translate)
            advanceUntilIdle()
            val translated = model.state.value.translationState
            check(translated is TranslationState.Translated)

            model(ImageDetailsAction.WillBePresented)
            advanceUntilIdle()

            assertEquals(translated, model.state.value.translationState)
        }

    /**
     * The model sees the photo without its watermark strip, and the strip comes back under the
     * colorized result — a 2×2 photo over a 2×1 strip gives a 2×3 result from a 2×2 gray frame.
     */
    @Test
    fun colorizeRunsTheModelOnTheContentAndKeepsTheResult() =
        reducerTest { scope ->
            val watermark = grayImage(width = 2, height = 1, values = listOf(40, 220))
            val source = WatermarkedImage(content = tinyPhoto(), watermark = watermark)
            val colorizationModel = ColorlessModel(claheClip = 1.0)
            val model = Harness(scope).makeModel(colorizationModel = colorizationModel)
            model(ImageDetailsAction.Internal.BwDetectionCompleted(isBw = true, image = source))

            model(ImageDetailsAction.Colorize)
            assertEquals(ColorizationState.Colorizing(source), model.state.value.colorizationState)

            val state = model.state
            assertTrue(eventually { state.value.colorizationState is ColorizationState.Ready })
            val result = (model.state.value.colorizationState as ColorizationState.Ready).colorized
            assertEquals(2, result.width)
            assertEquals(3, result.height)
            assertEquals(PlaneSize(2, 2), colorizationModel.receivedGray?.size)
            assertNull(model.state.value.colorizationPicker)
        }

    @Test
    fun colorizeOnTheResultTogglesTheOriginalWithoutRunningTheModel() =
        reducerTest { scope ->
            val colorized = tinyPhoto()
            val model = Harness(scope).makeModel()
            model(ImageDetailsAction.Internal.PixelsLoaded(tinyPhoto()))
            model(ImageDetailsAction.Internal.ColorizationCompleted(colorized, OK))
            assertEquals(
                ColorizationState.Ready(colorized, ColorizationState.Showing.Colorized, OK),
                model.state.value.colorizationState,
            )
            assertSame(colorized, model.state.value.colorizedImage)

            model(ImageDetailsAction.Colorize)
            assertEquals(
                ColorizationState.Ready(colorized, ColorizationState.Showing.Original, OK),
                model.state.value.colorizationState,
            )
            assertNull(model.state.value.colorizedImage)

            model(ImageDetailsAction.Colorize)
            assertEquals(
                ColorizationState.Ready(colorized, ColorizationState.Showing.Colorized, OK),
                model.state.value.colorizationState,
            )
            assertSame(colorized, model.state.value.colorizedImage)
        }

    @Test
    fun savingTheOriginalDoesNotMarkTheColorizedPhotoSaved() =
        reducerTest { scope ->
            val model = Harness(scope).makeModel()
            model(ImageDetailsAction.Internal.ImageSaved(ColorizationState.Showing.Original))
            assertTrue(model.state.value.isImageSaved)

            model(ImageDetailsAction.Internal.ColorizationCompleted(tinyPhoto(), OK))
            assertFalse(model.state.value.isImageSaved)

            model(ImageDetailsAction.Colorize)
            assertTrue(model.state.value.isImageSaved)
        }

    /** iOS releases the model; ours outlives the screen on the shared scope, so the view says
     *  `Closed` — either way the run stops at its next cancellation point. */
    @Test
    fun closingTheScreenCancelsTheRunningColorization() =
        reducerTest { scope ->
            val source = WatermarkedImage(content = tinyPhoto(), watermark = null)
            val colorizationModel = BlockingModel()
            val model = Harness(scope).makeModel(colorizationModel = colorizationModel)
            model(ImageDetailsAction.Internal.BwDetectionCompleted(isBw = true, image = source))
            model(ImageDetailsAction.Colorize)
            assertTrue(eventually { colorizationModel.isPredicting.isCompleted })

            model(ImageDetailsAction.Closed)
            advanceUntilIdle()
            colorizationModel.finishPrediction()

            assertTrue(eventually { colorizationModel.wasCancelled })
        }

    @Test
    fun failedColorizationRestoresTheButtonAndReportsTheError() =
        reducerTest { scope ->
            val source = WatermarkedImage(content = tinyPhoto(), watermark = null)
            val model = Harness(scope).makeModel(colorizationModel = ThrowingColorizationModel())
            model(ImageDetailsAction.Internal.BwDetectionCompleted(isBw = true, image = source))

            model(ImageDetailsAction.Colorize)

            assertTrue(eventually { model.state.value.alert != null })
            assertEquals(ColorizationState.Available(source), model.state.value.colorizationState)
        }

    /** A flat frame has no line to measure: iOS asserts and stays `.detecting`; a throw out of
     *  the effect here would take the process down with it. */
    @Test
    fun aFlatColorPhotoGetsNoButtonAndDoesNotCrash() =
        reducerTest { scope ->
            val model = Harness(scope).makeModel()
            model(ImageDetailsAction.WillBePresented)
            model(ImageDetailsAction.Internal.PixelsLoaded(grayImage(2, 2, List(4) { 0 })))

            repeat(SETTLE_POLLS) {
                advanceUntilIdle()
                Thread.sleep(EVENTUALLY_POLL_MS)
            }
            assertEquals(ColorizationState.Detecting, model.state.value.colorizationState)
        }
}

private class ThrowingColorizationModel : ColorizationModel {
    override val claheClip = 1.0
    override val boldness = 1f

    override suspend fun predict(gray: GrayPlane): ABPlanes = throw IOException("no color")
}

/**
 * iOS `eventually`: the pipeline runs on the default dispatcher, off the test scheduler, so the
 * test both drains its own scheduler and waits in real time for the result to come back.
 */
private fun TestScope.eventually(condition: () -> Boolean): Boolean {
    repeat(EVENTUALLY_ATTEMPTS) {
        advanceUntilIdle()
        if (condition()) return true
        Thread.sleep(EVENTUALLY_POLL_MS)
    }
    return false
}

private val OK = ColorizationCheck.Ok
private const val EVENTUALLY_ATTEMPTS = 500
private const val EVENTUALLY_POLL_MS = 10L
private const val SETTLE_POLLS = 20

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
        cachedDetails: ModelImageDetails? = null,
        detectLanguage: LanguageDetector = LanguageDetector { null },
        translate: Remote<TranslateParams, String> =
            Remote { TODO("this test dispatched .Translate without supplying a translate stub") },
        colorizationModel: ColorizationModel? = null,
    ): ImageDetailsModel =
        makeImageDetailsModel(
            modelImage = mockImage(cid = 1, title = imageTitle),
            remote =
                Remote { cid ->
                    requestedCids += cid
                    mockDetails(cid, description = detailsDescription)
                },
            cachedDetails = cachedDetails,
            detectLanguage = detectLanguage,
            openSource = "",
            isFavorite = { false },
            setFavorite = { _, _ -> },
            showOnMap = {},
            canOpenUrl = { true },
            urlOpener = { openedUrls += it },
            saveImage = { _, _ -> },
            shareImage = {},
            translate = translate,
            colorizationModel = { colorizationModel },
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
        watermarkHeight = 19,
        contentHeight = 800,
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
