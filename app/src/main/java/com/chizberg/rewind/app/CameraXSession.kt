package com.chizberg.rewind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.chizberg.rewind.features.comparison.CameraEvent
import com.chizberg.rewind.features.comparison.CameraSession
import com.chizberg.rewind.features.comparison.CapturedImage
import com.chizberg.rewind.features.comparison.DEFAULT_ZOOM_RATIO
import com.chizberg.rewind.features.comparison.Lens
import com.chizberg.rewind.features.comparison.lensBrackets
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * The half of the camera that only a composition can supply: the `PreviewView` its frames go to.
 * iOS has no counterpart — `CameraSession.makePreview()` *returns* the view there, while CameraX
 * binds to one that already exists (and to the lifecycle that owns it).
 *
 * The comparison screen casts its injected session to this to attach its viewfinder; a fake session
 * in a test simply is not one, and the screen draws nothing.
 */
interface CameraPreviewHost {
    fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    )

    fun detach()
}

/**
 * The CameraX side of the comparison screen: iOS's `CameraSession` (an `AVCaptureSession` with a
 * photo output) in Android form, behind the JVM-only [CameraSession] the reducer sees.
 *
 * One instance per presentation — a camera session is exactly as long-lived as the screen that
 * shows it (the M12 per-screen `GooglePlacesSuggestProvider` rule, not M13.5's per-graph one).
 *
 * Lenses: iOS enumerates the physical cameras of its virtual device; CameraX reports a continuous
 * zoom range, so the picker is built out of brackets of it (see `Lens.kt`).
 *
 * Rotation: iOS 7e4c3dd hands both the preview and the photo output the angle of an
 * `AVCaptureDevice.RotationCoordinator`. The CameraX equivalent is the use cases' `targetRotation`,
 * kept on the display's rotation for as long as a preview is attached. A quarter turn rebuilds the
 * activity and so re-attaches anyway; a half turn (landscape to the other landscape) does not, and
 * only the display listener sees it — without it the shot would come out upside down.
 */
class CameraXSession(
    context: Context,
) : CameraSession,
    CameraPreviewHost {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    // Hot, like iOS's SignalPipe: the reducer subscribes once when the model is built.
    private val eventsMutable =
        MutableSharedFlow<CameraEvent>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val events: Flow<CameraEvent> = eventsMutable

    private val permissionRequestsMutable =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val permissionRequests: Flow<Unit> = permissionRequestsMutable

    private val preview = Preview.Builder().build()

    // Latency over noise reduction: this is a "then and now" snapshot, and the shutter has to feel
    // as immediate as the blink that acknowledges it.
    private val imageCapture =
        ImageCapture
            .Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    override fun requestAccess() {
        permissionRequestsMutable.tryEmit(Unit)
    }

    override fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        surfaceProvider = previewView.surfaceProvider
        this.lifecycleOwner = lifecycleOwner
        followRotation(previewView)
        bind()
    }

    override fun detach() {
        displayListener?.let(displayManager::unregisterDisplayListener)
        displayListener = null
        surfaceProvider = null
        lifecycleOwner = null
        stop()
    }

    private fun followRotation(previewView: PreviewView) {
        // The activity's display rather than the view's: the view is not attached to a window yet
        // when the composition hands it over, and `View.display` is null until it is.
        val display = previewView.context.display ?: return

        fun update() {
            preview.targetRotation = display.rotation
            imageCapture.targetRotation = display.rotation
        }
        update()
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == display.displayId) update()
                }

                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit
            }
        displayListener?.let(displayManager::unregisterDisplayListener)
        displayManager.registerDisplayListener(listener, null)
        displayListener = listener
    }

    /** iOS `session.start()`. Binding is what starts a CameraX session, so this only fires once a
     *  preview has been attached — before that the reducer's call is a no-op, and the attach does
     *  the starting. */
    override fun start() {
        bind()
    }

    override fun stop() {
        provider?.unbindAll()
        camera = null
    }

    override fun setLens(lens: Lens) {
        val control =
            camera?.cameraControl ?: error("Camera is not ready")
        // iOS ramps at 6 zoom-factors per second (`device.ramp(toVideoZoomFactor:withRate:)`);
        // CameraX only sets a ratio, and the SDK's own transition is what the user sees.
        control.setZoomRatio(lens.zoomRatio)
    }

    override suspend fun capturePhoto(): CapturedImage =
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = runCatching { image.toUprightBitmap() }
                        image.close()
                        bitmap.fold(
                            onSuccess = { continuation.resume(CapturedBitmap(it)) },
                            onFailure = { continuation.resumeWithException(it) },
                        )
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    private fun bind() {
        val owner = lifecycleOwner ?: return
        val surface = surfaceProvider ?: return
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            runCatching {
                val provider = future.get()
                this.provider = provider
                preview.surfaceProvider = surface
                // Rebinding the same use cases without unbinding first is an error in CameraX.
                provider.unbindAll()
                val camera =
                    provider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                this.camera = camera
                camera.cameraControl.setZoomRatio(DEFAULT_ZOOM_RATIO)
                eventsMutable.tryEmit(camera.sessionReady())
            }.onFailure { eventsMutable.tryEmit(CameraEvent.Failed(it)) }
        }, mainExecutor)
    }
}

/** iOS `(lenses, wide: mainLens)` out of the freshly built session. */
private fun Camera.sessionReady(): CameraEvent.SessionReady {
    val zoom = cameraInfo.zoomState.value
    val lenses =
        lensBrackets(
            minZoomRatio = zoom?.minZoomRatio ?: DEFAULT_ZOOM_RATIO,
            maxZoomRatio = zoom?.maxZoomRatio ?: DEFAULT_ZOOM_RATIO,
        ).ifEmpty { listOf(Lens(title = "${DEFAULT_ZOOM_RATIO.toInt()}x", DEFAULT_ZOOM_RATIO)) }
    val mainLens = lenses.minBy { abs(it.zoomRatio - DEFAULT_ZOOM_RATIO) }
    return CameraEvent.SessionReady(lenses = lenses, mainLens = mainLens)
}

/** The captured frame the way up is up — CameraX hands back the sensor's own orientation plus the
 *  rotation that has to be applied to it. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bitmap = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
