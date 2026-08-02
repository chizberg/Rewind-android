package com.chizberg.rewind.features.comparison

import kotlinx.coroutines.flow.Flow

/**
 * A frame the platform produced — a photo out of the camera, a copy of the panorama's pixels, or
 * the rendered comparison canvas. Deliberately opaque: the reducer only passes it on (to the view,
 * which draws it, and to the saver/sharer, which encode it), so the bitmap itself never enters a
 * JVM-only file. Same divergence as M9's "no decoded image in the details state", one level down.
 */
interface CapturedImage

/**
 * What the camera pushes back at the reducer — the counterpart of iOS's synchronous
 * `CameraSession()` constructor, which either hands back a ready session or throws.
 *
 * On Android the session can only be built once a `PreviewView` exists to bind it to (that is what
 * makes the camera start and its zoom range known), and that view lives in composition — hence an
 * event rather than a return value.
 */
sealed interface CameraEvent {
    /** iOS `.sessionReady(session)`: the camera is bound and its lens picker is known. */
    data class SessionReady(
        val lenses: List<Lens>,
        val mainLens: Lens,
    ) : CameraEvent

    /** iOS's `CameraSession()` init throwing — the screen shows its access alert. */
    data class Failed(
        val error: Throwable,
    ) : CameraEvent
}

/**
 * The camera behind an interface, so the comparison reducer stays JVM-only and testable with a fake
 * (the M12 `PlacesSuggestProvider` / M13.5 `LocationSource` shape). Port of iOS `CameraSession`,
 * which wraps `AVCaptureSession` + `AVCapturePhotoOutput`; the CameraX implementation lives in
 * `app/CameraXSession.kt` and the view attaches its preview to it.
 *
 * [permissionRequests] has no iOS counterpart in this type: there the reducer asks
 * `AVCaptureDevice.requestAccess` straight from `viewWillAppear`. Android's runtime dialog needs an
 * Activity result launcher, which only exists in composition, so [requestAccess] merely rings this
 * flow and the screen's permission host answers with the verdict — the same split M13.5 made for
 * location access.
 */
interface CameraSession {
    /** Session lifecycle pushed from the platform; subscribed once, the way iOS's delegate
     *  signals are. */
    val events: Flow<CameraEvent>

    /** Rings when the reducer wants camera access; the screen's permission host runs the dialog. */
    val permissionRequests: Flow<Unit>

    /** iOS `AVCaptureDevice.requestAccess(for: .video)`, minus the verdict (see the type doc). */
    fun requestAccess()

    /** iOS `session.start()`: resume the preview (a no-op until the view has attached one). */
    fun start()

    /** iOS `session.stop()`. */
    fun stop()

    /** iOS `setLens(lens:animated:)` — throws, and the reducer turns that into its lens alert. */
    fun setLens(lens: Lens)

    /** iOS `capturePhoto()`. */
    suspend fun capturePhoto(): CapturedImage
}
