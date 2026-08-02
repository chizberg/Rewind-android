package com.chizberg.rewind.features.comparison

import com.chizberg.rewind.app.AlertParams
import com.chizberg.rewind.app.errorAlert
import com.chizberg.rewind.app.infoAlert
import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.StreetViewAvailability
import com.chizberg.rewind.features.details.pastVuUrl
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.cancellation.CancellationException

/** The then/now comparison reducer. Port of the iOS `ComparisonModel` typealias. */
typealias ComparisonModel = Reducer<ComparisonState, ComparisonAction>

/** Writes the composite (old photo + fresh frame) to the gallery. iOS `save(image:)`, M9's path. */
typealias CaptureSaver = suspend (CapturedImage) -> Unit

/** Hands the composite to the share sheet. iOS `makeShareVC(image:title:description:nil,url:)`. */
typealias CaptureSharer = suspend (image: CapturedImage, title: String, url: String) -> Unit

/**
 * What one presentation of the comparison screen consists of. Port of iOS `ComparisonViewDeps`,
 * which bundles the store with the platform handle the reducer's effects render (its
 * `UIHostingController`).
 *
 * Ours carries the same two halves in Android form: the [cameraSession] the view attaches its
 * preview to (null in Street View mode, mirroring iOS's optional `cameraSession` in state), and the
 * [renderer] the view registers its two snapshots with.
 */
class ComparisonViewDeps(
    val model: ComparisonModel,
    val cameraSession: CameraSession?,
    val renderer: ComparisonRenderer,
    private val onClose: () -> Unit = {},
) {
    /**
     * Releases everything this presentation owns — the orientation sensor subscription above all,
     * plus any effect still in flight. iOS needs no such call: its model, tracker and session are
     * all released with the dismissed screen. Ours hang off a scope, and a screen that is closed
     * has to hand it back (see the details reducer's `comparison(.dismiss)`).
     */
    fun close() {
        onClose()
    }
}

/**
 * The late-bound handle to what is on screen. Port of iOS's `weak var comparisonVC`, captured by
 * the reducer's effects before the view exists and assigned right after it is built — plus the
 * second thing iOS renders, its viewfinder.
 *
 * - [canvas] — iOS `renderView(comparisonVC.view)`: the whole 4:6 composite, what gets saved and
 *   shared.
 * - [viewfinder] — iOS `renderView(webView)`: the Street View "shot", a copy of the panorama's
 *   current pixels (the camera has a real photo output instead, see [CameraSession.capturePhoto]).
 *
 * Null means the screen is not composed; iOS asserts and throws in that case, and so do the effects
 * reading these.
 */
class ComparisonRenderer {
    var canvas: (suspend () -> CapturedImage)? = null
    var viewfinder: (suspend () -> CapturedImage)? = null
}

/**
 * State of one comparison screen. Port of iOS `ComparisonState`.
 *
 * Divergences from iOS:
 * - **no bitmaps in state.** iOS holds `oldUIImage` (the decoded old photo, handed over by the
 *   details screen) and a `UIView` inside `.viewfinder`; ours keeps [oldImage] as the model image
 *   only — Coil draws it from its path, the project-wide M9 divergence — and the viewfinder is a
 *   plain marker, since the preview is an `AndroidView` the screen composes for itself. The frame
 *   that *was* taken stays as an opaque [CapturedImage], never a `Bitmap`.
 * - **`availableLens` is stored, not computed.** iOS derives it from the session held in state; the
 *   Android session only learns its zoom range once bound, and reports it as an event.
 * - **no `shareVC`.** The Android share sheet is an `Intent`, not a presented controller (M9).
 */
data class ComparisonState(
    val oldImage: ModelImage,
    val captureMode: CaptureMode,
    val style: Style = Style.SideBySide,
    val captureState: CaptureState? = null,
    val orientation: Orientation = Orientation.Portrait,
    val alert: AlertParams? = null,
    val streetViewAvailability: StreetViewAvailability? = null,
    /** iOS's one channel for closing itself: the parent watches it (see the screen). */
    val shouldDismiss: Boolean = false,
    val shotsCount: Int = 0,
    val savesCount: Int = 0,
    val currentLens: Lens? = null,
    val availableLens: List<Lens> = emptyList(),
) {
    enum class Style { SideBySide, CardOnCard }

    /** Fixed by whoever opened the screen; never switched from inside it. */
    enum class CaptureMode { Camera, StreetView }

    sealed interface CaptureState {
        data object Viewfinder : CaptureState

        data class Taken(
            val capture: CapturedImage,
        ) : CaptureState
    }
}

sealed interface ComparisonAction {
    /** What the view dispatches. Port of iOS `ComparisonAction.External`. */
    sealed interface External : ComparisonAction {
        data class SetStyle(
            val style: ComparisonState.Style,
        ) : External

        data object Shoot : External

        data object Retake : External

        data class SetLens(
            val lens: Lens,
        ) : External

        data object ViewWillAppear : External

        /** iOS `.shareSheet(.present)`; there is no `.dismiss` half — see [ComparisonState]. */
        data object Share : External

        sealed interface Alert : External {
            data object PresentAccessError : Alert

            data class PresentLensError(
                val error: Throwable,
            ) : Alert

            data class PresentSavingImageError(
                val error: Throwable,
            ) : Alert

            data class PresentSharingImageError(
                val error: Throwable,
            ) : Alert

            /**
             * Carried over from iOS, where `makeStreetView` can fail to build its URL. Nothing
             * dispatches it here (the panorama is a native view, and "no panorama at this
             * location" is what the availability lookup already answers) — kept so both states
             * read the same, like M13.5's `LocationState.errorMessage`.
             */
            data class PresentStreetViewError(
                val error: Throwable,
            ) : Alert

            data object PresentStreetViewUnavailable : Alert

            data object Dismiss : Alert
        }
    }

    /** Port of iOS `ComparisonAction.Internal`. */
    sealed interface Internal : ComparisonAction {
        /** iOS `.sessionReady(session)`, arriving as an event (see [CameraEvent]). */
        data class SessionReady(
            val lenses: List<Lens>,
            val mainLens: Lens,
        ) : Internal

        data object VideoAccessGranted : Internal

        data class ImageTaken(
            val image: CapturedImage,
        ) : Internal

        data object ImageSaved : Internal

        data class OrientationChanged(
            val orientation: Orientation,
        ) : Internal

        data class StreetViewAvailabilityLoaded(
            val availability: StreetViewAvailability,
        ) : Internal

        data object SetupCapture : Internal
    }
}

/**
 * Builds one comparison screen. Port of iOS `makeComparisonViewDeps`.
 *
 * Everything platform-shaped is injected, as in M12/M13.5: the [cameraSession] (CameraX, and null
 * in Street View mode — iOS's `cameraSession` is likewise an optional it never fills there), the
 * [renderer] the view fills in with its snapshots, the device [orientation] signal (iOS's
 * `OrientationTracker`, fed in through `adding` exactly like its `.adding(signal:)`), and the
 * gallery / share sheet lambdas.
 *
 * [streetViewAvailability] arrives already bound to this photo's coordinate, mirroring iOS's
 * `streetViewAvailability.mapArgs { modelImage.coordinate }` at the call site.
 *
 * Divergence worth naming: **camera access.** iOS branches on
 * `AVCaptureDevice.authorizationStatus` inside `viewWillAppear` and asks for access right there;
 * Android's dialog needs an Activity launcher that only exists in composition, so `viewWillAppear`
 * rings [CameraSession.requestAccess] and the screen's permission host answers with either
 * `videoAccessGranted` or the access alert — the same split M13.5 made for location.
 *
 * As on iOS, **no effect here carries a stable id**: two quick shutter taps race rather than cancel
 * each other, and nothing is debounced. That is deliberate parity — do not copy the stable-id
 * pattern from the map / search reducers into this one.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught", "SwallowedException", "LongMethod")
fun makeComparisonModel(
    captureMode: ComparisonState.CaptureMode,
    oldImage: ModelImage,
    streetViewAvailability: Remote<Unit, StreetViewAvailability>,
    cameraSession: CameraSession?,
    renderer: ComparisonRenderer,
    orientation: Flow<Orientation>,
    saveImage: CaptureSaver,
    shareImage: CaptureSharer,
    scope: CoroutineScope,
): ComparisonModel =
    Reducer<ComparisonState, ComparisonAction>(
        initial = ComparisonState(oldImage = oldImage, captureMode = captureMode),
        scope = scope,
    ) { state, action, effect, asyncEffect ->
        when (action) {
            is ComparisonAction.External.SetStyle -> state.copy(style = action.style)

            ComparisonAction.External.Shoot -> {
                // The counter goes up before anything is captured: it drives the blink, which
                // acknowledges the *tap* (iOS fires its success haptic in the same spot; ours is
                // played by the view, which owns the haptic handle — as with M9's save).
                val mode = state.captureMode
                val hasViewfinder =
                    state.captureState is ComparisonState.CaptureState.Viewfinder
                // iOS's `guard let session = state.cameraSession` in Android terms: the session
                // exists from the start here, but it is only usable once bound — which is exactly
                // when the lens it reported was put into state.
                val isSessionReady = state.currentLens != null
                asyncEffect(
                    AsyncEffect.perform { send ->
                        try {
                            val image =
                                when (mode) {
                                    ComparisonState.CaptureMode.Camera -> {
                                        if (!isSessionReady) return@perform
                                        cameraSession?.capturePhoto() ?: return@perform
                                    }

                                    ComparisonState.CaptureMode.StreetView -> {
                                        // iOS `guard case let .viewfinder(webView)`.
                                        if (!hasViewfinder) return@perform
                                        renderer.viewfinder?.invoke() ?: return@perform
                                    }
                                }
                            send(ComparisonAction.Internal.ImageTaken(image))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // iOS routes every capture failure to the access alert.
                            send(ComparisonAction.External.Alert.PresentAccessError)
                        }
                    },
                )
                state.copy(shotsCount = state.shotsCount + 1)
            }

            ComparisonAction.External.Retake -> {
                asyncEffect(
                    AsyncEffect.anotherAction(action = ComparisonAction.Internal.SetupCapture),
                )
                state
            }

            is ComparisonAction.External.SetLens ->
                try {
                    cameraSession?.setLens(action.lens)
                    state.copy(currentLens = action.lens)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    asyncEffect(
                        AsyncEffect.anotherAction(
                            action = ComparisonAction.External.Alert.PresentLensError(e),
                        ),
                    )
                    state
                }

            ComparisonAction.External.ViewWillAppear ->
                when (state.captureMode) {
                    ComparisonState.CaptureMode.Camera -> {
                        effect { cameraSession?.requestAccess() }
                        state
                    }

                    ComparisonState.CaptureMode.StreetView -> {
                        // Two independent effects, as on iOS: the panorama is built at once and
                        // the availability lookup catches up with it.
                        asyncEffect(
                            AsyncEffect.perform { send ->
                                try {
                                    send(
                                        ComparisonAction.Internal.StreetViewAvailabilityLoaded(
                                            streetViewAvailability.load(Unit),
                                        ),
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Swallowed on purpose, as iOS spells out: the user is only
                                    // blocked when Street View is *surely* unavailable, never
                                    // because the metadata call itself failed.
                                }
                            },
                        )
                        asyncEffect(
                            AsyncEffect.anotherAction(
                                action = ComparisonAction.Internal.SetupCapture,
                            ),
                        )
                        state
                    }
                }

            ComparisonAction.External.Share -> {
                val title = state.oldImage.title
                val url = pastVuUrl(state.oldImage.cid)
                asyncEffect(
                    AsyncEffect.perform { send ->
                        try {
                            shareImage(renderer.renderCanvas(), title, url)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            send(
                                ComparisonAction.External.Alert.PresentSharingImageError(e),
                            )
                        }
                    },
                )
                state
            }

            ComparisonAction.External.Alert.PresentAccessError ->
                state.copy(
                    alert =
                        infoAlert(
                            title = "Unable to use the camera",
                            message = "You can check camera permissions in Settings",
                        ),
                )

            is ComparisonAction.External.Alert.PresentLensError ->
                state.copy(alert = errorAlert("Unable to switch lens", action.error))

            is ComparisonAction.External.Alert.PresentSavingImageError ->
                state.copy(alert = errorAlert("Unable to save image", action.error))

            is ComparisonAction.External.Alert.PresentSharingImageError ->
                state.copy(alert = errorAlert("Unable to share image", action.error))

            is ComparisonAction.External.Alert.PresentStreetViewError ->
                state.copy(alert = errorAlert("Street View Error", action.error))

            ComparisonAction.External.Alert.PresentStreetViewUnavailable ->
                state.copy(
                    alert =
                        infoAlert(
                            title = "Google Street View Unavailable",
                            message = "Google Street View is not available for this location.",
                        ),
                )

            ComparisonAction.External.Alert.Dismiss ->
                // Dismissing the "unavailable" alert closes the whole screen — there is nothing to
                // compare against. The flag is never cleared: the reducer is thrown away with the
                // presentation, as on iOS.
                state.copy(
                    alert = null,
                    shouldDismiss =
                        state.shouldDismiss ||
                            state.streetViewAvailability is StreetViewAvailability.Unavailable,
                )

            ComparisonAction.Internal.VideoAccessGranted -> {
                asyncEffect(
                    AsyncEffect.anotherAction(action = ComparisonAction.Internal.SetupCapture),
                )
                state
            }

            is ComparisonAction.Internal.SessionReady ->
                state.copy(availableLens = action.lenses, currentLens = action.mainLens)

            is ComparisonAction.Internal.ImageTaken -> {
                effect { cameraSession?.stop() }
                // iOS renders the very same composite twice — once implicitly for the preview, and
                // here again for the gallery. Ours renders it once, off the canvas the screen has
                // just redrawn with the frame in it.
                asyncEffect(
                    AsyncEffect.perform { send ->
                        try {
                            saveImage(renderer.renderCanvas())
                            send(ComparisonAction.Internal.ImageSaved)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            send(ComparisonAction.External.Alert.PresentSavingImageError(e))
                        }
                    },
                )
                state.copy(captureState = ComparisonState.CaptureState.Taken(action.image))
            }

            ComparisonAction.Internal.ImageSaved -> state.copy(savesCount = state.savesCount + 1)

            is ComparisonAction.Internal.OrientationChanged ->
                state.copy(orientation = action.orientation)

            is ComparisonAction.Internal.StreetViewAvailabilityLoaded -> {
                if (action.availability is StreetViewAvailability.Unavailable) {
                    asyncEffect(
                        AsyncEffect.anotherAction(
                            action =
                                ComparisonAction.External.Alert.PresentStreetViewUnavailable,
                        ),
                    )
                }
                state.copy(streetViewAvailability = action.availability)
            }

            ComparisonAction.Internal.SetupCapture ->
                when (state.captureMode) {
                    ComparisonState.CaptureMode.Camera ->
                        if (cameraSession == null) {
                            state // iOS `guard let session = state.cameraSession else { return }`
                        } else {
                            effect { cameraSession.start() }
                            state.copy(
                                captureState = ComparisonState.CaptureState.Viewfinder,
                            )
                        }

                    ComparisonState.CaptureMode.StreetView -> {
                        // Defensive on iOS too: the mode is fixed at presentation, so there is no
                        // camera to shut down here in practice.
                        effect { cameraSession?.stop() }
                        state.copy(captureState = ComparisonState.CaptureState.Viewfinder)
                    }
                }
        }
    }.adding(orientation) { ComparisonAction.Internal.OrientationChanged(it) }
        .adding(cameraSession?.events ?: emptyFlow()) { event ->
            when (event) {
                is CameraEvent.SessionReady ->
                    ComparisonAction.Internal.SessionReady(event.lenses, event.mainLens)

                // iOS: a session that cannot be built shows the access alert.
                is CameraEvent.Failed -> ComparisonAction.External.Alert.PresentAccessError
            }
        }

/** iOS asserts and throws `"Comparison VC is missing"` when its weak view handle is gone. */
private suspend fun ComparisonRenderer.renderCanvas(): CapturedImage =
    canvas?.invoke() ?: error("Comparison canvas is missing")

/** The year a Street View panorama was shot, or null when there is none. iOS's fileprivate
 *  `StreetViewAvailability.year`, used for the "now" label. */
val StreetViewAvailability?.year: Int?
    get() = (this as? StreetViewAvailability.Available)?.year
