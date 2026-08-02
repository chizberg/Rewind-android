package com.chizberg.rewind.features.comparison.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chizberg.rewind.app.CameraPreviewHost
import com.chizberg.rewind.app.CapturedBitmap
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.comparison.CameraSession
import com.chizberg.rewind.features.comparison.ComparisonRenderer
import com.chizberg.rewind.features.map.ui.toLatLng
import com.google.android.gms.maps.StreetViewPanorama
import com.google.android.gms.maps.StreetViewPanoramaOptions
import com.google.android.gms.maps.StreetViewPanoramaView
import com.google.android.gms.maps.model.StreetViewPanoramaCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * How long a snapshot waits for the canvas to redraw before settling for what is already in the
 * layer. A save comes right after the frame lands and redraws within a frame or two; a share
 * happens on a canvas that may not be redrawing at all, and must not hang on one.
 *
 * It waits for the *second* redraw, not the first. The card-on-card arrangement scales each card
 * through a `graphicsLayer` of its own, and a nested layer is empty on the frame it first records
 * into — capturing after one draw wrote a composite with a hollow card where the fresh shot should
 * be (the side-by-side arrangement, which has no nested layer, was fine). The timeout is the floor
 * either way, so a still canvas costs the same quarter second it always did.
 */
private const val REDRAW_TIMEOUT_MS = 250L

/**
 * The live camera half of the canvas. Port of iOS `CameraSession.makePreview()` — there the session
 * hands back a view, here it binds to one the composition owns (see [CameraPreviewHost]).
 *
 * `FILL_CENTER` is iOS's `videoGravity = .resizeAspectFill`: the preview fills its 4:3 frame.
 *
 * Filling a frame of a different aspect than the camera's means `PreviewView` draws *past its own
 * bounds* and leaves the clipping to whoever hosts it — and nobody here did, so the preview spilled
 * over the year divider and up under the display cutout. Both halves of the fix are needed:
 * [Modifier.clipToBounds] to actually cut it (an `AndroidView` is not clipped by default), and
 * [PreviewView.ImplementationMode.COMPATIBLE] so that there is something to cut — the default
 * `SurfaceView` is composited as a layer of its own and ignores any clip above it. A `TextureView`
 * draws like an ordinary view. The preview is never what gets captured (the recorder only runs on
 * a taken frame), so nothing downstream cares which one draws it.
 */
@Composable
fun CameraViewfinder(
    session: CameraSession?,
    modifier: Modifier = Modifier,
) {
    val host = session as? CameraPreviewHost ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            PreviewView(context)
                .apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { host.attach(it, lifecycleOwner) }
        },
        onRelease = { host.detach() },
    )
}

/**
 * The Street View half of the canvas — a **native** `StreetViewPanoramaView`, not the `WKWebView` +
 * Embed API iframe iOS builds in `StreetViewFactory`. That is the repo's standing divergence
 * ("Нативный StreetViewPanoramaView вместо WebView-embed"), which takes precedence over the design
 * pack's decision #2.
 *
 * The view is driven directly rather than through maps-compose's `StreetView` composable, which
 * **crashes on exactly the case this screen has to survive**: its
 * `StreetViewPanoramaPropertiesNode` registers an `OnStreetViewPanoramaChangeListener` that assigns
 * the reported location to a non-null field, and the SDK reports `null` when there is no panorama
 * at the coordinate (still true in 8.4.0). Nothing here reads the camera state, so the fix is to
 * own the view and register no listener at all — the "is there a panorama" question is answered by
 * the metadata lookup in the reducer, as on iOS.
 *
 * The panorama starts at the photo's coordinate, facing the way the photo was shot when PastVu
 * knows the direction (iOS passes `heading` only when `dir != nil` and lets Google choose
 * otherwise).
 *
 * Its "shot" is a copy of the pixels on screen — iOS renders its web view into an image, and a
 * panorama draws into a surface of its own, which only [PixelCopy] can read back (a Compose
 * snapshot of the tree would come back with a hole where it is).
 */
@Composable
fun StreetViewViewfinder(
    image: ModelImage,
    renderer: ComparisonRenderer,
    zoom: PanoramaZoom,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val panorama =
        remember(context) {
            StreetViewPanoramaView(
                context,
                StreetViewPanoramaOptions()
                    .position(image.coordinate.toLatLng())
                    .apply {
                        image.dir?.angleDegrees?.let { heading ->
                            panoramaCamera(
                                StreetViewPanoramaCamera
                                    .Builder()
                                    .bearing(heading)
                                    .build(),
                            )
                        }
                    },
            )
        }
    PanoramaLifecycle(panorama)

    DisposableEffect(renderer, panorama) {
        renderer.viewfinder = { CapturedBitmap(panorama.copyPixels()) }
        onDispose { renderer.viewfinder = null }
    }

    DisposableEffect(zoom, panorama) {
        panorama.getStreetViewPanoramaAsync { api -> zoom.bind(api) }
        onDispose { zoom.bind(null) }
    }

    AndroidView(modifier = modifier.clipToBounds(), factory = { panorama })
}

/**
 * The panorama's field of view, driven from the chrome around the canvas.
 *
 * Android-only: iOS's Street View is an Embed API iframe with Google's own controls inside it, so
 * there is nothing to port. A panorama opens at its widest, which flattens the very perspective the
 * screen exists to compare, and pinch alone is awkward next to a photo you are trying to line up.
 *
 * Late-bound like [ComparisonRenderer], and for the same reason: the buttons exist before the SDK
 * hands over the panorama. The zoom is read back off the panorama on every press rather than kept
 * here, so pinching and the buttons cannot drift apart; [current] only mirrors it, to tell the
 * buttons when they have hit an end.
 */
@Stable
class PanoramaZoom {
    // Snapshot state, not a plain field: the buttons are enabled by its arrival, and the panorama
    // shows up long after they are first composed. A plain field left them dead — nothing told
    // Compose to read [canZoomIn] again, and `current` cannot stand in for the news because the
    // panorama opens at the zoom the buttons already assume.
    private var panorama by mutableStateOf<StreetViewPanorama?>(null)

    var current by mutableFloatStateOf(MIN_ZOOM)
        private set

    val canZoomIn: Boolean get() = panorama != null && current < MAX_ZOOM
    val canZoomOut: Boolean get() = panorama != null && current > MIN_ZOOM

    internal fun bind(api: StreetViewPanorama?) {
        panorama = api
        current = api?.panoramaCamera?.zoom ?: MIN_ZOOM
        // The camera reports back for pinch as much as for `animateTo`. Its parameter is spelled
        // nullable on purpose: this SDK is the one that hands `null` to a Kotlin lambda that never
        // asked for it (see the panorama-change listener maps-compose crashes on).
        api?.setOnStreetViewPanoramaCameraChangeListener { camera: StreetViewPanoramaCamera? ->
            camera?.let { current = it.zoom }
        }
    }

    fun step(delta: Float) {
        val api = panorama ?: return
        val camera = api.panoramaCamera
        val target = (camera.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        api.animateTo(
            StreetViewPanoramaCamera.Builder(camera).zoom(target).build(),
            ZOOM_DURATION_MS,
        )
    }

    companion object {
        /** Street View zoom is exponential — a whole step halves the field of view — so the ceiling
         *  is low and the step is a half. Past 4 the imagery is upscaled mush on most panoramas. */
        const val ZOOM_STEP = 0.5f
        private const val MIN_ZOOM = 0f
        private const val MAX_ZOOM = 4f
        private const val ZOOM_DURATION_MS = 200L
    }
}

/**
 * The panorama's pixels, in two passes.
 *
 * Copying the *window* the way this first did comes back with a hole: the panorama draws into a
 * `SurfaceView`, a layer of its own that the window's own buffer never contains, so the composite
 * got a black rectangle wearing Google's logo. [PixelCopy] can read that layer, but only when
 * handed the surface itself — hence the first pass.
 *
 * The second pass is everything else the view holds: the logo and the attribution links are plain
 * views, drawn into the window and therefore *missing* from the surface copy. They go onto a
 * transparent bitmap of their own and are composited over the panorama — drawing them straight
 * onto it would risk the `SurfaceView` punching its hole through the pixels just copied. iOS's
 * web-view screenshot carries the same attribution, and Google's terms expect it to survive into
 * whatever the image becomes.
 */
private suspend fun StreetViewPanoramaView.copyPixels(): Bitmap {
    val surfaceView = findSurfaceView() ?: error("Street View has no surface to copy")
    val panorama =
        Bitmap.createBitmap(
            surfaceView.width.coerceAtLeast(1),
            surfaceView.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
    suspendCancellableCoroutine { continuation ->
        PixelCopy.request(
            surfaceView,
            panorama,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("Unable to copy the panorama ($result)"),
                    )
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    val chrome = Bitmap.createBitmap(panorama.width, panorama.height, Bitmap.Config.ARGB_8888)
    draw(Canvas(chrome))
    Canvas(panorama).drawBitmap(chrome, 0f, 0f, null)
    chrome.recycle()
    return panorama
}

/** The Maps SDK builds its own hierarchy; the surface it renders into is somewhere inside it. */
private fun View.findSurfaceView(): SurfaceView? =
    when (this) {
        is SurfaceView -> this
        is ViewGroup ->
            (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findSurfaceView() }

        else -> null
    }

/**
 * Drives the panorama view's own lifecycle, which a plain `AndroidView` does not — the same
 * observer maps-compose installs, minus the composition that registers the crashing listener. The
 * `ON_CREATE` guard is theirs too: a lifecycle that never reached `onDestroy` must not be re-created
 * or the view comes back blank.
 *
 * Low-memory callbacks are deliberately not forwarded: this panorama lives for one screen, unlike
 * the map, which is up for the whole session.
 */
@Composable
private fun PanoramaLifecycle(panorama: StreetViewPanoramaView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val previousEvent = remember { mutableStateOf(Lifecycle.Event.ON_CREATE) }
    DisposableEffect(lifecycle, panorama) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE ->
                        if (previousEvent.value != Lifecycle.Event.ON_STOP) {
                            panorama.onCreate(Bundle())
                        }

                    Lifecycle.Event.ON_START -> panorama.onStart()
                    Lifecycle.Event.ON_RESUME -> panorama.onResume()
                    Lifecycle.Event.ON_PAUSE -> panorama.onPause()
                    Lifecycle.Event.ON_STOP -> panorama.onStop()
                    // Destruction is the composable's own business, see onDispose.
                    Lifecycle.Event.ON_DESTROY -> Unit
                    Lifecycle.Event.ON_ANY -> Unit
                }
                previousEvent.value = event
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            panorama.onDestroy()
        }
    }
}

/**
 * Records the canvas into a layer and registers the snapshot the reducer's save / share effects
 * call. Port of iOS's `weak var comparisonVC` plus its `renderView(view:)`.
 *
 * Two things it does that iOS gets for free:
 * - it waits for the *next* draw before reading the layer, which is iOS's
 *   `drawHierarchy(afterScreenUpdates: true)` — the save fires the instant the frame enters state,
 *   before Compose has drawn it;
 * - the recording is only switched on with [enabled] (i.e. once a frame has been taken), so the
 *   live viewfinder — a `SurfaceView`, whose pixels are not Compose's to copy — never goes through
 *   the layer at all. The screen only ever captures a taken frame, exactly as iOS does.
 *
 * Divergence to note: iOS renders its composite at a fixed `scale = 3`; this records at the
 * device's own density, so the saved file is as big as the screen it was framed on.
 */
@Composable
fun canvasRecorder(
    renderer: ComparisonRenderer,
    enabled: Boolean,
): Modifier {
    val layer = rememberGraphicsLayer()
    val drawn = remember { MutableStateFlow(0L) }

    DisposableEffect(renderer, layer) {
        renderer.canvas = {
            val seen = drawn.value
            withTimeoutOrNull(REDRAW_TIMEOUT_MS) { drawn.first { it > seen + 1 } }
            CapturedBitmap(layer.toImageBitmap().asAndroidBitmap())
        }
        onDispose { renderer.canvas = null }
    }

    return if (!enabled) {
        Modifier
    } else {
        Modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            drawn.value += 1
        }
    }
}
