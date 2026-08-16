package com.chizberg.rewind.features.details

import com.chizberg.rewind.app.AlertParams
import com.chizberg.rewind.app.errorAlert
import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.core.util.Haptics
import com.chizberg.rewind.core.util.OrientationLock
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.features.comparison.ComparisonState
import com.chizberg.rewind.features.comparison.ComparisonViewDeps
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/** Opens an external URL (browser / map app). Port of iOS `UrlOpener`; the Compose side wraps it in
 * an `ACTION_VIEW` intent. */
typealias UrlOpener = (String) -> Unit

/**
 * Writes the photo to the device gallery. iOS hands its decoded `UIImage` to `PHPhotoLibrary`; ours
 * only names the image (the reducer holds no bitmap — the project-wide Coil divergence), and the app
 * layer pulls the pixels through the shared loader and inserts them into MediaStore. Throws on
 * failure, which the reducer turns into iOS's "Unable to save image" alert.
 */
typealias ImageSaver = suspend (ModelImage) -> Unit

/** Hands the photo to the system share sheet. Port of iOS `makeShareVC` + its `.sheet`. */
typealias ImageSharer = suspend (ShareContent) -> Unit

/**
 * Builds one comparison screen for this photo. iOS calls `makeComparisonViewDeps` inline here (the
 * camera and the Street View lookup are values it can construct anywhere); ours is a lambda from
 * the graph, so the platform halves — CameraX, the orientation sensor, the gallery — stay out of
 * this JVM-only reducer.
 */
typealias ComparisonFactory = (ModelImage, ComparisonState.CaptureMode) -> ComparisonViewDeps

/**
 * What the share sheet carries. iOS passes four activity items (image, title, description, URL);
 * Android takes one image stream plus one text blob, so the app layer joins these.
 *
 * [description] is the raw PastVu HTML — iOS shares the *plain* text of its attributed string, and
 * the stripping happens where the platform's HTML parser lives (the app layer), not in this
 * JVM-only reducer.
 */
data class ShareContent(
    val image: ModelImage,
    val title: String,
    val description: String?,
    val url: String,
)

/**
 * Text + target language for one Cloud Translation call. The type lives in the network layer, where
 * iOS keeps it too (`Network/RewindRemotes.swift`, beside the remote that consumes it); this alias
 * re-exports the name into the one feature that uses it, so the reducer's signature reads exactly
 * as iOS's does.
 */
typealias TranslateParams = com.chizberg.rewind.network.TranslateParams

/**
 * One cached title/description translation. Port of iOS `ImageDetailsState.Translation`.
 *
 * Divergence from iOS: raw strings, not `AttributedString` — HTML parsing for descriptions
 * (including a translated one) lives in the UI layer on Android (`ImageDetailsView.kt`), not in
 * this JVM-only reducer, per the M9 divergence note in CLAUDE.md.
 */
data class Translation(
    val title: String,
    val description: String,
)

/** Port of iOS `ImageDetailsState.TranslationState`. */
sealed interface TranslationState {
    /** The description is already in the app's language (or none was detected) — no button. */
    data object NotAvailable : TranslationState

    /** Translate is offered but hasn't been requested (or was reverted via "Show Original"). */
    data object Available : TranslationState

    /** A translate request is in flight. */
    data object Translating : TranslationState

    /** The cached-or-fresh translation is currently shown. */
    data class Translated(
        val translation: Translation,
    ) : TranslationState
}

/** The image-details reducer. Port of iOS `ImageDetailsModel`. */
typealias ImageDetailsModel = Reducer<ImageDetailsState, ImageDetailsAction>

/**
 * State of one image-details screen. Port of iOS `ImageDetailsState`, trimmed to M9.
 *
 * Divergences from iOS:
 * - **no decoded image in state.** iOS holds `uiImage`/`cachedLowResImage` (`UIImage`) because its
 *   ImageLoader hands back the bitmap; ours follows the project-wide Coil divergence — the picture
 *   is loaded from [ModelImage.imagePath] by Coil in the view. Share / save (which need the actual
 *   pixels) go through the injected [ImageSaver] / [ImageSharer], which pull them from the same
 *   loader; the reducer only tracks that a save succeeded ([isImageSaved], as on iOS).
 * - **no `Identified` wrapper** around the nested details / alert: Compose overlays key off content
 *   presence, so plain nullables suffice.
 */
data class ImageDetailsState(
    val image: ModelImage,
    val openSource: String,
    val isFavorite: Boolean,
    val actionButtons: List<ImageDetailsAction.Button>,
    val details: ModelImageDetails? = null,
    val isImageSaved: Boolean = false,
    val loadingAnotherImage: Boolean = false,
    val mapOptionsPresented: Boolean = false,
    val fullscreenPresented: Boolean = false,
    val anotherImageModel: ImageDetailsModel? = null,
    /** iOS `comparisonDeps`: the comparison screen while it is up (M14). */
    val comparison: ComparisonViewDeps? = null,
    val alert: AlertParams? = null,
    /** Port of iOS `translationState`. Starts at iOS's `.notAvailable` and is settled once the
     * details land and their description has been run past the language detector. */
    val translationState: TranslationState = TranslationState.NotAvailable,
    /** Port of iOS `cachedTranslation`: survives "Show Original", spent only by a fresh
     * `makeImageDetailsModel` (a new screen instance). */
    val cachedTranslation: Translation? = null,
)

sealed interface ImageDetailsAction {
    /** The screen is about to appear: kick off the details load. */
    data object WillBePresented : ImageDetailsAction

    /** A link tapped inside the (HTML) description — recurse if it points at a pastvu photo,
     * otherwise open it in the browser. */
    data class DescriptionLink(
        val url: String,
    ) : ImageDetailsAction

    /** The action-grid buttons, in iOS's order. */
    enum class Button {
        Favorite,
        CompareCamera,
        CompareStreetView,
        ShowOnMap,
        Share,
        SaveImage,
        ViewOnWeb,
        Route,
    }

    data class OnButton(
        val button: Button,
    ) : ImageDetailsAction

    data class SetMapOptionsVisibility(
        val visible: Boolean,
    ) : ImageDetailsAction

    /** A map app picked from the "find route" menu. */
    data class MapAppSelected(
        val app: MapApp,
    ) : ImageDetailsAction

    sealed interface FullscreenPreview : ImageDetailsAction {
        data object Present : FullscreenPreview

        data object Dismiss : FullscreenPreview

        /** The save button of the viewer's own chrome (iOS `ZoomableImageScreen.saveImage`). */
        data object SaveImage : FullscreenPreview
    }

    /** iOS `ImageComparison`: the then/now screen, opened in one of its two fixed modes. */
    sealed interface Comparison : ImageDetailsAction {
        data class Present(
            val mode: ComparisonState.CaptureMode,
        ) : Comparison

        data object Dismiss : Comparison
    }

    sealed interface AnotherImage : ImageDetailsAction {
        data class Present(
            val details: ModelImageDetails,
            val source: String,
        ) : AnotherImage

        data object Dismiss : AnotherImage
    }

    sealed interface Alert : ImageDetailsAction {
        data class Present(
            val params: AlertParams?,
        ) : Alert

        data object Dismiss : Alert
    }

    /** Port of iOS top-level `.translate`. Cache hit (a non-null `cachedTranslation`) resolves
     * synchronously; otherwise kicks off the title+description translate request. */
    data object Translate : ImageDetailsAction

    /** Port of iOS top-level `.showTranslationOriginal`: revert to the untranslated text without
     * discarding the cache. */
    data object ShowTranslationOriginal : ImageDetailsAction

    sealed interface Internal : ImageDetailsAction {
        /** Both save entry points (the action tile and the viewer's chrome) funnel through here. */
        data object SaveImage : Internal

        data object ImageSaved : Internal

        data class DetailsLoaded(
            val details: ModelImageDetails,
        ) : Internal

        data class AnotherImageLoadFailed(
            val error: Throwable,
        ) : Internal

        /**
         * The description's language came back from the detector — the one action iOS has no
         * counterpart for. There, `detectLanguage` is a synchronous call inside `apply(details:)`,
         * so `translationState` is settled in the very same reduce that stores the details; ML Kit
         * answers through a `Task`, so the verdict has to come back as its own action. Null is
         * "couldn't tell" (iOS's `dominantLanguage == nil`) — including a detector that failed.
         */
        data class DescriptionLanguageDetected(
            val language: DetectedLanguage?,
        ) : Internal

        /** Port of iOS `.internal(.translationComplete(_:))`. */
        data class TranslationComplete(
            val translation: Translation,
        ) : Internal

        /** Port of iOS `.internal(.translationFailed(_:))`. */
        data class TranslationFailed(
            val error: Throwable,
        ) : Internal
    }
}

/**
 * How sure the detector has to be before its verdict is trusted, straight from iOS
 * (`descriptionLang.confidence >= 0.9`). Below it the language is treated as unknown, which offers
 * the translation rather than hiding it — the safer way to be wrong.
 */
private const val MIN_LANGUAGE_CONFIDENCE = 0.9f

/** The fallback target language, for callers that don't name one (reducer tests). */
private const val DEFAULT_APP_LANGUAGE = "en"

/** iOS `ImageDetailsView.TransitionSource.descriptionLink`; also the nested screen's open source. */
const val DESCRIPTION_LINK_SOURCE = "descriptionLink"

/**
 * Builds an image-details reducer. Port of iOS `makeImageDetailsModel`.
 *
 * Dependencies are injected as lambdas (iOS threads a `FavoritesModel`, a `urlOpener`, etc.). The
 * favorites gateway is a query + a setter: [isFavorite] snapshots the star at open time and each
 * nested screen re-queries for its own image; [setFavorite] persists a toggle. The real
 * `FavoritesModel` supplies both in M10; for now they can be stubbed.
 *
 * Recursion: a description link to a pastvu photo loads that photo and presents it as a nested
 * details screen ([ImageDetailsState.anotherImageModel]) built with the same dependencies. The
 * nested [remote] short-circuits the just-loaded details so the child's own load doesn't refetch.
 *
 * Translation takes three of those dependencies: [detectLanguage] decides whether the button is
 * offered at all, [translate] fetches the title and the description, and [appLanguage] is both the
 * target it asks for and what a detected language is compared against. iOS reads its `appLang` off
 * `Bundle.main.preferredLocalizations` inside this file; here the equivalent needs Android
 * resources, so the app layer resolves it and passes it in (see `AppGraph`).
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList")
fun makeImageDetailsModel(
    modelImage: ModelImage,
    remote: Remote<Int, ModelImageDetails>,
    openSource: String,
    isFavorite: (ModelImage) -> Boolean,
    setFavorite: (ModelImage, Boolean) -> Unit,
    showOnMap: (Coordinate) -> Unit,
    canOpenUrl: (String) -> Boolean,
    urlOpener: UrlOpener,
    saveImage: ImageSaver,
    shareImage: ImageSharer,
    // The two comparison dependencies default to "there is no camera here", so a caller that never
    // opens the screen (a reducer test) does not have to fabricate one. The graph always passes
    // both.
    makeComparison: ComparisonFactory = { _, _ -> error("No comparison factory was injected") },
    setOrientationLock: (OrientationLock?) -> Unit = {},
    translate: Remote<TranslateParams, String>,
    // "Couldn't tell", the same as a description in an unrecognised language: a caller that never
    // shows the button (a reducer test) needs no ML Kit. The graph always passes the real one.
    detectLanguage: LanguageDetector = LanguageDetector { null },
    appLanguage: String = DEFAULT_APP_LANGUAGE,
    haptics: Haptics = Haptics.None,
    extractModelImage: (ModelImageDetails) -> ModelImage,
    scope: CoroutineScope,
): ImageDetailsModel =
    Reducer(
        initial =
            ImageDetailsState(
                image = modelImage,
                openSource = openSource,
                isFavorite = isFavorite(modelImage),
                actionButtons =
                    listOf(
                        ImageDetailsAction.Button.Favorite,
                        // iOS offers these two on the phone only (`withUIIdiom(phone:pad: nil)`);
                        // an Android tablet has a camera and Street View works there, so they are
                        // offered on every form factor.
                        ImageDetailsAction.Button.CompareCamera,
                        ImageDetailsAction.Button.CompareStreetView,
                        ImageDetailsAction.Button.ShowOnMap,
                        ImageDetailsAction.Button.Share,
                        ImageDetailsAction.Button.SaveImage,
                        ImageDetailsAction.Button.ViewOnWeb,
                        ImageDetailsAction.Button.Route,
                    ),
            ),
        scope = scope,
    ) { state, action, effect, asyncEffect ->
        when (action) {
            ImageDetailsAction.WillBePresented -> {
                asyncEffect(
                    AsyncEffect.perform { send ->
                        try {
                            send(
                                ImageDetailsAction.Internal.DetailsLoaded(
                                    remote.load(modelImage.cid),
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            send(present(errorAlert("Unable to load image info", e)))
                        }
                    },
                )
                state
            }

            is ImageDetailsAction.DescriptionLink -> {
                val cid = pastvuPhotoCid(action.url)
                if (cid == null) {
                    effect { urlOpener(action.url) }
                    state
                } else {
                    asyncEffect(
                        AsyncEffect.perform { send ->
                            try {
                                val details = remote.load(cid)
                                send(
                                    ImageDetailsAction.AnotherImage.Present(
                                        details,
                                        DESCRIPTION_LINK_SOURCE,
                                    ),
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                send(ImageDetailsAction.Internal.AnotherImageLoadFailed(e))
                            }
                        },
                    )
                    state.copy(loadingAnotherImage = true)
                }
            }

            is ImageDetailsAction.OnButton ->
                when (action.button) {
                    ImageDetailsAction.Button.Favorite -> {
                        val next = !state.isFavorite
                        // The tap is acknowledged after the write, inside the same effect iOS puts
                        // its `impactOccurred()` in — not before it.
                        asyncEffect(
                            AsyncEffect.perform {
                                setFavorite(modelImage, next)
                                haptics.impactLight()
                            },
                        )
                        state.copy(isFavorite = next)
                    }

                    ImageDetailsAction.Button.CompareCamera -> {
                        asyncEffect(
                            AsyncEffect.anotherAction(
                                action =
                                    ImageDetailsAction.Comparison.Present(
                                        ComparisonState.CaptureMode.Camera,
                                    ),
                            ),
                        )
                        state
                    }

                    ImageDetailsAction.Button.CompareStreetView -> {
                        asyncEffect(
                            AsyncEffect.anotherAction(
                                action =
                                    ImageDetailsAction.Comparison.Present(
                                        ComparisonState.CaptureMode.StreetView,
                                    ),
                            ),
                        )
                        state
                    }

                    ImageDetailsAction.Button.ShowOnMap -> {
                        effect { showOnMap(modelImage.coordinate) }
                        state
                    }

                    ImageDetailsAction.Button.Share -> {
                        // iOS builds the share sheet only once the details (and the decoded image)
                        // are in; ours shares what it has — the description is the only part that
                        // waits on the load, and a tile that silently does nothing reads as broken.
                        val content =
                            ShareContent(
                                image = state.image,
                                title = state.image.title,
                                description = state.details?.description,
                                url = pastVuUrl(state.image.cid),
                            )
                        asyncEffect(
                            AsyncEffect.perform { send ->
                                try {
                                    shareImage(content)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    send(present(errorAlert("Unable to share image", e)))
                                }
                            },
                        )
                        state
                    }

                    ImageDetailsAction.Button.SaveImage -> {
                        asyncEffect(
                            AsyncEffect.anotherAction(
                                action = ImageDetailsAction.Internal.SaveImage,
                            ),
                        )
                        state
                    }

                    ImageDetailsAction.Button.ViewOnWeb -> {
                        effect { urlOpener(pastVuUrl(state.image.cid)) }
                        state
                    }

                    ImageDetailsAction.Button.Route -> {
                        asyncEffect(
                            AsyncEffect.anotherAction(
                                action = ImageDetailsAction.SetMapOptionsVisibility(true),
                            ),
                        )
                        state
                    }
                }

            is ImageDetailsAction.MapAppSelected -> {
                val link =
                    action.app.coordinateLink(
                        modelImage.coordinate.latitude,
                        modelImage.coordinate.longitude,
                    )
                // Nothing on screen says the route failed — the buzz is the whole feedback, as on
                // iOS. See the manifest's `<queries>`: without it every link looks unopenable.
                if (canOpenUrl(link)) {
                    effect { urlOpener(link) }
                } else {
                    effect { haptics.error() }
                }
                state
            }

            is ImageDetailsAction.SetMapOptionsVisibility ->
                state.copy(mapOptionsPresented = action.visible)

            ImageDetailsAction.FullscreenPreview.Present -> state.copy(fullscreenPresented = true)

            ImageDetailsAction.FullscreenPreview.Dismiss -> state.copy(fullscreenPresented = false)

            ImageDetailsAction.FullscreenPreview.SaveImage -> {
                asyncEffect(
                    AsyncEffect.anotherAction(action = ImageDetailsAction.Internal.SaveImage),
                )
                state
            }

            is ImageDetailsAction.Comparison.Present -> {
                // iOS gates on its decoded `uiImage` and does nothing (bar an error haptic) until
                // the full-quality photo is in. Ours has no bitmap to gate on — Coil draws the old
                // half from its path, showing the cached rendition until the big one lands — so
                // the screen simply opens.
                effect { setOrientationLock(OrientationLock.Portrait) }
                state.copy(comparison = makeComparison(modelImage, action.mode))
            }

            ImageDetailsAction.Comparison.Dismiss -> {
                val comparison = state.comparison
                effect {
                    setOrientationLock(null)
                    // ARC does this on iOS: the dismissed screen takes its camera session and its
                    // orientation tracker with it (see ComparisonViewDeps.close).
                    comparison?.close()
                }
                state.copy(comparison = null)
            }

            is ImageDetailsAction.AnotherImage.Present -> {
                val nested =
                    makeImageDetailsModel(
                        modelImage = extractModelImage(action.details),
                        // Short-circuit the already-loaded details so the child's own load is free.
                        remote =
                            Remote { cid ->
                                if (cid == action.details.cid) action.details else remote.load(cid)
                            },
                        openSource = action.source,
                        isFavorite = isFavorite,
                        setFavorite = setFavorite,
                        showOnMap = showOnMap,
                        canOpenUrl = canOpenUrl,
                        urlOpener = urlOpener,
                        saveImage = saveImage,
                        shareImage = shareImage,
                        makeComparison = makeComparison,
                        setOrientationLock = setOrientationLock,
                        translate = translate,
                        detectLanguage = detectLanguage,
                        appLanguage = appLanguage,
                        extractModelImage = extractModelImage,
                        scope = scope,
                    )
                state.copy(loadingAnotherImage = false, anotherImageModel = nested)
            }

            ImageDetailsAction.AnotherImage.Dismiss -> state.copy(anotherImageModel = null)

            is ImageDetailsAction.Alert.Present ->
                action.params?.let { state.copy(alert = it) } ?: state

            ImageDetailsAction.Alert.Dismiss -> state.copy(alert = null)

            ImageDetailsAction.Translate -> {
                val description = state.details?.description
                val cached = state.cachedTranslation
                when {
                    // iOS asserts here and returns; the button is never drawn without a
                    // description, so in a shipped build this is simply nothing happening.
                    description == null -> state

                    // Translated once, free ever after — "Show Original" keeps the cache, so
                    // toggling back and forth never touches the network again.
                    cached != null ->
                        state.copy(translationState = TranslationState.Translated(cached))

                    else -> {
                        val title = modelImage.title
                        // No id, hence no deduplication: a second tap would run a second request,
                        // exactly as on iOS. What keeps that from happening is the button, which
                        // is gone while `Translating` — not the reducer.
                        asyncEffect(
                            AsyncEffect.perform { send ->
                                try {
                                    send(
                                        ImageDetailsAction.Internal.TranslationComplete(
                                            fetchTranslation(
                                                translate = translate,
                                                title = title,
                                                description = description,
                                                target = appLanguage,
                                            ),
                                        ),
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    send(ImageDetailsAction.Internal.TranslationFailed(e))
                                }
                            },
                        )
                        state.copy(translationState = TranslationState.Translating)
                    }
                }
            }

            ImageDetailsAction.ShowTranslationOriginal ->
                // The cache deliberately survives: see the `Translate` branch above.
                state.copy(translationState = TranslationState.Available)

            ImageDetailsAction.Internal.SaveImage -> {
                val image = state.image
                asyncEffect(
                    AsyncEffect.perform { send ->
                        try {
                            saveImage(image)
                            send(ImageDetailsAction.Internal.ImageSaved)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            send(present(errorAlert("Unable to save image", e)))
                        }
                    },
                )
                state
            }

            ImageDetailsAction.Internal.ImageSaved -> {
                effect { haptics.success() }
                state.copy(isImageSaved = true)
            }

            // Port of iOS `apply(details:to:)`, split in two because the detector is asynchronous
            // here: the payload lands now, the translation verdict in
            // `DescriptionLanguageDetected` below. A photo without a description takes iOS's else
            // branch straight away (`.available` — moot, since the button hangs off the
            // description block that isn't there).
            is ImageDetailsAction.Internal.DetailsLoaded -> {
                val description = action.details.description
                if (description == null) {
                    state.copy(
                        details = action.details,
                        translationState = TranslationState.Available,
                    )
                } else {
                    asyncEffect(
                        AsyncEffect.perform { send ->
                            val language =
                                try {
                                    detectLanguage.detect(description)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (
                                    @Suppress("SwallowedException") e: Exception,
                                ) {
                                    // A detector that fell over knows as little as one that had no
                                    // guess: iOS's nil branch, which offers the translation.
                                    null
                                }
                            send(
                                ImageDetailsAction.Internal.DescriptionLanguageDetected(language),
                            )
                        },
                    )
                    state.copy(details = action.details)
                }
            }

            is ImageDetailsAction.Internal.DescriptionLanguageDetected -> {
                val language = action.language
                val sameLanguage =
                    language != null &&
                        language.confidence >= MIN_LANGUAGE_CONFIDENCE &&
                        language.languageCode == appLanguage
                state.copy(
                    translationState =
                        if (sameLanguage) {
                            TranslationState.NotAvailable
                        } else {
                            TranslationState.Available
                        },
                )
            }

            is ImageDetailsAction.Internal.AnotherImageLoadFailed -> {
                asyncEffect(
                    AsyncEffect.anotherAction(
                        action = present(errorAlert("Unable to load image data", action.error)),
                    ),
                )
                state.copy(loadingAnotherImage = false)
            }

            is ImageDetailsAction.Internal.TranslationComplete ->
                state.copy(
                    translationState = TranslationState.Translated(action.translation),
                    cachedTranslation = action.translation,
                )

            is ImageDetailsAction.Internal.TranslationFailed -> {
                asyncEffect(
                    AsyncEffect.anotherAction(
                        action =
                            present(errorAlert("Unable to translate description", action.error)),
                    ),
                )
                // Back to `Available`, so the button returns and the tap can be retried.
                state.copy(translationState = TranslationState.Available)
            }
        }
    }

/**
 * Both halves of a translation, fetched at once. Port of the two `async let`s in iOS's `.translate`
 * effect: the title and the description go out together rather than one after the other (half the
 * latency), and a failure of either cancels the other and surfaces as the failure of the whole —
 * which is what Swift's structured concurrency does there, and what `coroutineScope` does here.
 */
private suspend fun fetchTranslation(
    translate: Remote<TranslateParams, String>,
    title: String,
    description: String,
    target: String,
): Translation =
    coroutineScope {
        val translatedDescription =
            async { translate.load(TranslateParams(text = description, target = target)) }
        val translatedTitle =
            async { translate.load(TranslateParams(text = title, target = target)) }
        Translation(
            title = translatedTitle.await(),
            description = translatedDescription.await(),
        )
    }

/** iOS `pastVuURL(cid:)`: the public web page for a photo. */
fun pastVuUrl(cid: Int): String = "https://pastvu.com/$cid"

private const val PASTVU_HOST = "pastvu.com"

/**
 * The photo cid a description link points at, or null if it isn't a pastvu photo page. Mirrors the
 * iOS parse in `descriptionLink`: host `pastvu.com`, path `/p/<cid>` (photo id), cid an integer.
 * A profile link (`/u/<name>`), an external host, or a malformed URL all yield null → browser.
 */
private fun pastvuPhotoCid(url: String): Int? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.host != PASTVU_HOST) return null
    val segments =
        uri.path
            .orEmpty()
            .split("/")
            .filter { it.isNotEmpty() }
    if (segments.size != 2 || segments[0] != "p") return null
    return segments[1].toIntOrNull()
}

private fun present(params: AlertParams?): ImageDetailsAction =
    ImageDetailsAction.Alert.Present(params)
