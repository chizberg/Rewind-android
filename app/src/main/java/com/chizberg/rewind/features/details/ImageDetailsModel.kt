package com.chizberg.rewind.features.details

import com.chizberg.rewind.app.AlertParams
import com.chizberg.rewind.app.errorAlert
import com.chizberg.rewind.colorization.ColorizationCheck
import com.chizberg.rewind.colorization.ColorizationModel
import com.chizberg.rewind.colorization.WatermarkedImage
import com.chizberg.rewind.colorization.colorize
import com.chizberg.rewind.colorization.image.ArgbImage
import com.chizberg.rewind.colorization.isMonochrome
import com.chizberg.rewind.colorization.splitWatermark
import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.core.util.Haptics
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.features.colorization.ColorizationPickerModel
import com.chizberg.rewind.features.comparison.ComparisonState
import com.chizberg.rewind.features.comparison.ComparisonViewDeps
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/** Opens an external URL (browser / map app). Port of iOS `UrlOpener`; the Compose side wraps it in
 * an `ACTION_VIEW` intent. */
typealias UrlOpener = (String) -> Unit

/**
 * Writes the photo to the device gallery. iOS hands its decoded `UIImage` to `PHPhotoLibrary`; ours
 * names the image (the reducer holds no bitmap of the original — the project-wide Coil divergence),
 * and the app layer pulls the pixels through the shared loader and inserts them into MediaStore —
 * unless the second argument carries pixels of our own making (the colorized photo), which are
 * saved as they are. Throws on failure, which the reducer turns into iOS's "Unable to save image"
 * alert.
 */
typealias ImageSaver = suspend (ModelImage, ArgbImage?) -> Unit

/** Hands the photo to the system share sheet. Port of iOS `makeShareVC` + its `.sheet`. */
typealias ImageSharer = suspend (ShareContent) -> Unit

/**
 * Builds one comparison screen for this photo. iOS calls `makeComparisonViewDeps` inline here (the
 * camera and the Street View lookup are values it can construct anywhere); ours is a lambda from
 * the graph, so the platform halves — CameraX, the orientation sensor, the gallery — stay out of
 * this JVM-only reducer.
 */
typealias ComparisonFactory =
    (ModelImage, ComparisonState.CaptureMode, ArgbImage?) -> ComparisonViewDeps

/**
 * The photo's own pixels, full size, for colorization. iOS has them as the `UIImage` its loader
 * hands the screen; ours come from Coil's cache once the view has shown the photo (see
 * [ImageDetailsAction.ImageLoaded]).
 */
typealias PixelLoader = suspend (ModelImage) -> ArgbImage

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
    /** The colorized photo when that is the one on screen — iOS shares `displayedImage`. */
    val colorized: ArgbImage? = null,
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

/** Port of iOS `ImageDetailsState.ColorizationState`. */
sealed interface ColorizationState {
    /** Which of the two photos is on screen once a colorized one exists. */
    enum class Showing { Original, Colorized }

    /** The photo or its details are not in yet. */
    data object None : ColorizationState

    data object Detecting : ColorizationState

    /** The photo has color already: no button. */
    data object NotAvailable : ColorizationState

    data class Available(
        val image: WatermarkedImage,
    ) : ColorizationState

    data class Colorizing(
        val image: WatermarkedImage,
    ) : ColorizationState

    data class Ready(
        val colorized: ArgbImage,
        val showing: Showing,
        val check: ColorizationCheck,
    ) : ColorizationState
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
 *   loader; the reducer only counts successful saves ([imageSaveCount], as on iOS).
 * - **no `Identified` wrapper** around the nested details / alert: Compose overlays key off content
 *   presence, so plain nullables suffice.
 */
data class ImageDetailsState(
    val image: ModelImage,
    val openSource: String,
    val isFavorite: Boolean,
    val actionButtons: List<ImageDetailsAction.Button>,
    val details: ModelImageDetails? = null,
    /** iOS `uiImage`: the photo's pixels, read once the view has shown it — only colorization
     *  needs them; the screen itself draws through Coil. */
    val pixels: ArgbImage? = null,
    /** iOS `imageSaveCounts`: every save bumps the count of the version on screen, so the
     *  fullscreen viewer's banner re-announces a second save, and saving the original does not
     *  mark the colorized photo saved. [isImageSaved] is derived from it, as on iOS. */
    val imageSaveCounts: Map<ColorizationState.Showing, Int> = emptyMap(),
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
    val colorizationState: ColorizationState = ColorizationState.None,
    /** iOS `colorizationPicker`: the model picker, opened by a colorize tap without a model. */
    val colorizationPicker: ColorizationPickerModel? = null,
) {
    val displayedVersion: ColorizationState.Showing
        get() {
            val ready = colorizationState as? ColorizationState.Ready
            return ready?.showing ?: ColorizationState.Showing.Original
        }

    val imageSaveCount: Int get() = imageSaveCounts[displayedVersion] ?: 0

    val isImageSaved: Boolean get() = imageSaveCount > 0

    /** iOS `colorizedImage`: the colorized photo while it is the one on screen. */
    val colorizedImage: ArgbImage?
        get() {
            val ready = colorizationState as? ColorizationState.Ready ?: return null
            return ready.colorized.takeIf { ready.showing == ColorizationState.Showing.Colorized }
        }

    val isColorizing: Boolean get() = colorizationState is ColorizationState.Colorizing
}

sealed interface ImageDetailsAction {
    /** The screen is about to appear: kick off the details load. */
    data object WillBePresented : ImageDetailsAction

    /**
     * iOS `imageLoaded`: the full-quality photo is on screen. iOS's loader hands the bitmap over
     * with it; ours is Coil's, so the reducer reads the pixels from the cache that load just
     * filled — rather than starting a second download of the same photo next to the view's.
     */
    data object ImageLoaded : ImageDetailsAction

    /** iOS `.colorize`: run the model on the photo, or switch between it and the result. */
    data object Colorize : ImageDetailsAction

    data object DismissColorizationCheck : ImageDetailsAction

    sealed interface ColorizationPicker : ImageDetailsAction {
        data object Present : ColorizationPicker

        data object Dismiss : ColorizationPicker
    }

    /**
     * The screen went away for good. iOS ties the colorization run to the model's lifetime
     * (`tetheredEffects`); ours outlives the screen on the shared scope, so the view says when it
     * is gone, and the run is cancelled.
     */
    data object Closed : ImageDetailsAction

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

        data class ImageSaved(
            val version: ColorizationState.Showing,
        ) : Internal

        data class PixelsLoaded(
            val pixels: ArgbImage,
        ) : Internal

        data class BwDetectionCompleted(
            val isBw: Boolean,
            val image: WatermarkedImage,
        ) : Internal

        data class ColorizationCompleted(
            val colorized: ArgbImage,
            val check: ColorizationCheck,
        ) : Internal

        data class ColorizationFailed(
            val error: Throwable,
        ) : Internal

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
@Suppress("TooGenericExceptionCaught", "LongParameterList", "ThrowsCount")
fun makeImageDetailsModel(
    modelImage: ModelImage,
    remote: Remote<Int, ModelImageDetails>,
    // iOS `cachedDetails`: details already known when the screen is built (the loader's cache, or
    // the photo a description link has just loaded) land in the very first state, so the screen
    // renders complete in its first frame and presenting it asks for nothing again.
    cachedDetails: ModelImageDetails? = null,
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
    makeComparison: ComparisonFactory = { _, _, _ -> error("No comparison factory was injected") },
    translate: Remote<TranslateParams, String>,
    // "Couldn't tell", the same as a description in an unrecognised language: a caller that never
    // shows the button (a reducer test) needs no ML Kit. The graph always passes the real one.
    detectLanguage: LanguageDetector = LanguageDetector { null },
    appLanguage: String = DEFAULT_APP_LANGUAGE,
    haptics: Haptics = Haptics.None,
    // Colorization: the photo's pixels, the picked model (null when none is installed) and the
    // picker for when it is not. A caller that never colorizes (a reducer test) needs none of them.
    loadPixels: PixelLoader = { error("No pixel loader was injected") },
    colorizationModel: () -> ColorizationModel? = { null },
    makeColorizationPicker: (colorize: () -> Unit) -> ColorizationPickerModel = {
        error("No colorization picker factory was injected")
    },
    extractModelImage: (ModelImageDetails) -> ModelImage,
    scope: CoroutineScope,
): ImageDetailsModel {
    // iOS `modelRef`: the picker's "Colorize" comes back into this reducer.
    var modelRef: ImageDetailsModel? = null
    return Reducer<ImageDetailsState, ImageDetailsAction>(
        initial =
            ImageDetailsState(
                image = modelImage,
                openSource = openSource,
                isFavorite = isFavorite(modelImage),
                // A photo without coordinates (reached through a description link) has nowhere
                // to show on the map, look at in Street View or route to.
                actionButtons =
                    buildList {
                        add(ImageDetailsAction.Button.Favorite)
                        add(ImageDetailsAction.Button.CompareCamera)
                        if (modelImage.coordinate != null) {
                            add(ImageDetailsAction.Button.CompareStreetView)
                            add(ImageDetailsAction.Button.ShowOnMap)
                        }
                        add(ImageDetailsAction.Button.Share)
                        add(ImageDetailsAction.Button.SaveImage)
                        add(ImageDetailsAction.Button.ViewOnWeb)
                        if (modelImage.coordinate != null) add(ImageDetailsAction.Button.Route)
                    },
            ).let { initial -> cachedDetails?.let { initial.applying(it) } ?: initial },
        scope = scope,
    ) { state, action, effect, asyncEffect ->
        when (action) {
            ImageDetailsAction.WillBePresented -> {
                val seeded = state.details
                if (seeded == null) {
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
                } else {
                    // Seeded details skip the request, not the language verdict: iOS settles that
                    // synchronously in `apply(details:to:)`, ours is the detector's round trip.
                    seeded.description?.let {
                        asyncEffect(detectDescriptionLanguage(it, detectLanguage))
                    }
                }
                state
            }

            ImageDetailsAction.ImageLoaded -> {
                if (state.pixels == null) {
                    asyncEffect(
                        AsyncEffect.perform(PIXELS_EFFECT_ID) { send ->
                            try {
                                val pixels = loadPixels(modelImage)
                                send(ImageDetailsAction.Internal.PixelsLoaded(pixels))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (
                                @Suppress("SwallowedException") e: Exception,
                            ) {
                                // No pixels, no colorize button: the photo itself is on screen.
                            }
                        },
                    )
                }
                state
            }

            ImageDetailsAction.Colorize ->
                when (val colorization = state.colorizationState) {
                    is ColorizationState.Available -> {
                        val colorizer = colorizationModel()
                        if (colorizer == null) {
                            asyncEffect(
                                AsyncEffect.anotherAction(
                                    action = ImageDetailsAction.ColorizationPicker.Present,
                                ),
                            )
                            state
                        } else {
                            asyncEffect(colorizing(colorization.image, colorizer))
                            state.copy(
                                colorizationState =
                                    ColorizationState.Colorizing(colorization.image),
                            )
                        }
                    }

                    is ColorizationState.Ready ->
                        state.copy(colorizationState = colorization.toggled())

                    ColorizationState.None,
                    ColorizationState.Detecting,
                    ColorizationState.NotAvailable,
                    is ColorizationState.Colorizing,
                    -> state
                }

            ImageDetailsAction.DismissColorizationCheck -> {
                // iOS asserts on any other state: the banner only exists on a result.
                val ready = state.colorizationState as? ColorizationState.Ready
                if (ready == null) {
                    state
                } else {
                    state.copy(colorizationState = ready.copy(check = ColorizationCheck.Ok))
                }
            }

            ImageDetailsAction.ColorizationPicker.Present ->
                if (state.colorizationPicker != null) {
                    state
                } else {
                    state.copy(
                        colorizationPicker =
                            makeColorizationPicker {
                                modelRef?.invoke(ImageDetailsAction.ColorizationPicker.Dismiss)
                                modelRef?.invoke(ImageDetailsAction.Colorize)
                            },
                    )
                }

            ImageDetailsAction.ColorizationPicker.Dismiss -> state.copy(colorizationPicker = null)

            ImageDetailsAction.Closed -> {
                asyncEffect(AsyncEffect.cancel(COLORIZATION_EFFECT_ID))
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
                        modelImage.coordinate?.let { effect { showOnMap(it) } }
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
                                colorized = state.colorizedImage,
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
                    modelImage.coordinate?.let {
                        action.app.coordinateLink(it.latitude, it.longitude)
                    }
                // Nothing on screen says the route failed — the buzz is the whole feedback, as on
                // iOS. See the manifest's `<queries>`: without it every link looks unopenable.
                if (link != null && canOpenUrl(link)) {
                    effect { urlOpener(link) }
                } else {
                    effect { haptics.error() }
                }
                state
            }

            is ImageDetailsAction.SetMapOptionsVisibility ->
                state.copy(mapOptionsPresented = action.visible)

            // iOS keeps the viewer shut while the model runs: the photo is about to change under it.
            ImageDetailsAction.FullscreenPreview.Present ->
                if (state.isColorizing) state else state.copy(fullscreenPresented = true)

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
                state.copy(
                    comparison = makeComparison(modelImage, action.mode, state.colorizedImage),
                )
            }

            ImageDetailsAction.Comparison.Dismiss -> {
                val comparison = state.comparison
                effect {
                    // ARC does this on iOS: the dismissed screen takes its camera session with it
                    // (see ComparisonViewDeps.close).
                    comparison?.close()
                }
                state.copy(comparison = null)
            }

            is ImageDetailsAction.AnotherImage.Present -> {
                val nested =
                    makeImageDetailsModel(
                        modelImage = extractModelImage(action.details),
                        remote = remote,
                        cachedDetails = action.details,
                        openSource = action.source,
                        isFavorite = isFavorite,
                        setFavorite = setFavorite,
                        showOnMap = showOnMap,
                        canOpenUrl = canOpenUrl,
                        urlOpener = urlOpener,
                        saveImage = saveImage,
                        shareImage = shareImage,
                        makeComparison = makeComparison,
                        translate = translate,
                        detectLanguage = detectLanguage,
                        appLanguage = appLanguage,
                        haptics = haptics,
                        loadPixels = loadPixels,
                        colorizationModel = colorizationModel,
                        makeColorizationPicker = makeColorizationPicker,
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
                val colorized = state.colorizedImage
                val version = state.displayedVersion
                asyncEffect(
                    AsyncEffect.perform { send ->
                        try {
                            saveImage(image, colorized)
                            send(ImageDetailsAction.Internal.ImageSaved(version))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            send(present(errorAlert("Unable to save image", e)))
                        }
                    },
                )
                state
            }

            is ImageDetailsAction.Internal.ImageSaved -> {
                effect { haptics.success() }
                val count = (state.imageSaveCounts[action.version] ?: 0) + 1
                state.copy(imageSaveCounts = state.imageSaveCounts + (action.version to count))
            }

            is ImageDetailsAction.Internal.PixelsLoaded ->
                state
                    .copy(pixels = action.pixels)
                    .checkingColorizationAvailability(asyncEffect)

            is ImageDetailsAction.Internal.BwDetectionCompleted ->
                state.copy(
                    colorizationState =
                        if (action.isBw) {
                            ColorizationState.Available(action.image)
                        } else {
                            ColorizationState.NotAvailable
                        },
                )

            is ImageDetailsAction.Internal.ColorizationCompleted -> {
                effect { haptics.success() }
                state.copy(
                    colorizationState =
                        ColorizationState.Ready(
                            colorized = action.colorized,
                            showing = ColorizationState.Showing.Colorized,
                            check = action.check,
                        ),
                )
            }

            is ImageDetailsAction.Internal.ColorizationFailed -> {
                asyncEffect(
                    AsyncEffect.anotherAction(
                        action = present(errorAlert("Unable to colorize image", action.error)),
                    ),
                )
                // Back to the button, so the tap can be retried.
                val colorizing = state.colorizationState as? ColorizationState.Colorizing
                if (colorizing == null) {
                    state
                } else {
                    state.copy(colorizationState = ColorizationState.Available(colorizing.image))
                }
            }

            // Port of iOS `apply(details:to:)`, split in two because the detector is asynchronous
            // here: the payload lands now, the translation verdict in
            // `DescriptionLanguageDetected` below. A photo without a description takes iOS's else
            // branch straight away (`.available` — moot, since the button hangs off the
            // description block that isn't there).
            is ImageDetailsAction.Internal.DetailsLoaded -> {
                action.details.description?.let {
                    asyncEffect(detectDescriptionLanguage(it, detectLanguage))
                }
                state.applying(action.details).checkingColorizationAvailability(asyncEffect)
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
    }.also { modelRef = it }
}

/**
 * Port of iOS `checkColorizationAvailability`: once both the photo's pixels and its details are in
 * (either may land first), cut the watermark off by the heights PastVu reports and offer the button
 * only for a photo that has no color yet.
 */
private fun ImageDetailsState.checkingColorizationAvailability(
    asyncEffect: (AsyncEffect<ImageDetailsAction>) -> Unit,
): ImageDetailsState {
    if (colorizationState != ColorizationState.None) return this
    val pixels = pixels ?: return this
    val details = details ?: return this
    asyncEffect(
        AsyncEffect.perform { send ->
            try {
                val image =
                    withContext(Dispatchers.Default) {
                        splitWatermark(pixels, details.watermarkHeight, details.contentHeight)
                    }
                val isBw = withContext(Dispatchers.Default) { isMonochrome(image.content) }
                send(ImageDetailsAction.Internal.BwDetectionCompleted(isBw, image))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                // A single flat color or a photo of a couple of pixels has no line to measure.
                // iOS asserts and stays at `.detecting`: no button, nothing else on screen.
            }
        },
    )
    return copy(colorizationState = ColorizationState.Detecting)
}

/** The run itself. Port of the iOS `.colorize` effect: the model on the photo without its strip,
 *  then the strip stitched back under the result. */
@Suppress("TooGenericExceptionCaught")
private fun colorizing(
    image: WatermarkedImage,
    model: ColorizationModel,
): AsyncEffect<ImageDetailsAction> =
    AsyncEffect.perform(COLORIZATION_EFFECT_ID) { send ->
        try {
            val result = colorize(image.content, model)
            val stitched =
                withContext(Dispatchers.Default) { image.withContent(result.image).stitched() }
            send(ImageDetailsAction.Internal.ColorizationCompleted(stitched, result.check))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ImageDetailsAction.Internal.ColorizationFailed(e))
        }
    }

/** iOS's `.ready` branch of `.colorize`: the other one of the two photos. */
private fun ColorizationState.Ready.toggled(): ColorizationState.Ready =
    copy(
        showing =
            when (showing) {
                ColorizationState.Showing.Colorized -> ColorizationState.Showing.Original
                ColorizationState.Showing.Original -> ColorizationState.Showing.Colorized
            },
    )

private const val PIXELS_EFFECT_ID = "pixels"
private const val COLORIZATION_EFFECT_ID = "colorization"

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

/**
 * The synchronous half of iOS `apply(details:to:)`: the payload lands now; the translation verdict
 * follows from [detectDescriptionLanguage]. A photo without a description takes iOS's else branch
 * straight away (`.available` — moot, since the button hangs off the description block that isn't
 * there).
 */
private fun ImageDetailsState.applying(details: ModelImageDetails): ImageDetailsState =
    if (details.description == null) {
        copy(details = details, translationState = TranslationState.Available)
    } else {
        copy(details = details)
    }

/**
 * The asynchronous half of iOS `apply(details:to:)` — there `NLLanguageRecognizer` answers inline,
 * here ML Kit answers through a task, so the verdict comes back as its own action.
 */
@Suppress("TooGenericExceptionCaught")
private fun detectDescriptionLanguage(
    description: String,
    detectLanguage: LanguageDetector,
): AsyncEffect<ImageDetailsAction> =
    AsyncEffect.perform { send ->
        val language =
            try {
                detectLanguage.detect(description)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("SwallowedException") e: Exception,
            ) {
                // A detector that fell over knows as little as one that had no guess: iOS's nil
                // branch, which offers the translation.
                null
            }
        send(ImageDetailsAction.Internal.DescriptionLanguageDetected(language))
    }

private fun present(params: AlertParams?): ImageDetailsAction =
    ImageDetailsAction.Alert.Present(params)
