package com.chizberg.rewind.features.details

import com.chizberg.rewind.app.AlertParams
import com.chizberg.rewind.app.errorAlert
import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
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
 * - **translation** ([details]-driven `TranslationState`) and **comparison** (`comparisonDeps`) are
 *   M15 / M14; their branches join then.
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
    val alert: AlertParams? = null,
)

sealed interface ImageDetailsAction {
    /** The screen is about to appear: kick off the details load. */
    data object WillBePresented : ImageDetailsAction

    /** A link tapped inside the (HTML) description — recurse if it points at a pastvu photo,
     * otherwise open it in the browser. */
    data class DescriptionLink(
        val url: String,
    ) : ImageDetailsAction

    /** The action-grid buttons that are implemented in M9 (compare buttons arrive with M14). */
    enum class Button { Favorite, ShowOnMap, Share, SaveImage, ViewOnWeb, Route }

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
    }
}

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
                        asyncEffect(AsyncEffect.perform { setFavorite(modelImage, next) })
                        state.copy(isFavorite = next)
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
                if (canOpenUrl(link)) effect { urlOpener(link) }
                // iOS fires an error haptic when no app can open the link; that lands in the UI.
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
                        extractModelImage = extractModelImage,
                        scope = scope,
                    )
                state.copy(loadingAnotherImage = false, anotherImageModel = nested)
            }

            ImageDetailsAction.AnotherImage.Dismiss -> state.copy(anotherImageModel = null)

            is ImageDetailsAction.Alert.Present ->
                action.params?.let { state.copy(alert = it) } ?: state

            ImageDetailsAction.Alert.Dismiss -> state.copy(alert = null)

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

            // iOS also fires a success haptic here; ours plays it in the view, where the haptic
            // feedback handle lives.
            ImageDetailsAction.Internal.ImageSaved -> state.copy(isImageSaved = true)

            is ImageDetailsAction.Internal.DetailsLoaded -> state.copy(details = action.details)

            is ImageDetailsAction.Internal.AnotherImageLoadFailed -> {
                asyncEffect(
                    AsyncEffect.anotherAction(
                        action = present(errorAlert("Unable to load image data", action.error)),
                    ),
                )
                state.copy(loadingAnotherImage = false)
            }
        }
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
