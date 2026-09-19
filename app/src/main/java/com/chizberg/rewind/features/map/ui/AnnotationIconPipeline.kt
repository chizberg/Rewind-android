package com.chizberg.rewind.features.map.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalContext
import androidx.tracing.Trace
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.RgbaColor
import com.chizberg.rewind.network.ImageQuality
import com.chizberg.rewind.network.imageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * At most this many icon landings commit per frame; the rest wait for following frames. A perfetto
 * capture of a 486-cluster wave showed landings arriving in bursts (OkHttp releases ~5 fetches at
 * a time, Coil decodes 4 at a time) and each burst frame paying up to ~19ms of marker
 * recompositions plus a texture-upload spike on the RenderThread — the measured cause of the
 * dropped frames.
 *
 * Three, not the original ten: ten was tuned against a ~17ms frame, but the target devices run
 * 120Hz (8.3ms budget), and the M16 perf pass measured burst frames on a 120Hz release build at
 * 10–19ms of `Record View#draw()` — about 1–2ms per landing — plus a concurrent 10–15ms animation
 * phase. Three keeps the landing share near ~3–4ms. The wave stretches from ~10 to ~28 frames at
 * 120Hz (≈230ms — the length of the pop-in animation itself), which reads as a cascade, not a lag.
 */
private const val LANDINGS_PER_FRAME = 3

// Async systrace section spanning one cluster thumbnail's Coil fetch+decode (cookie = path hash).
private const val TRACE_CLUSTER_FETCH = "Rewind:clusterFetch"

/**
 * Android-only performance machinery for annotation icons — no iOS counterpart (iOS annotation
 * views assign a `UIImage` and are done). Its job is to keep a dense wave of appearing markers
 * from dropping frames:
 *
 * - Every bitmap is rasterized on [Dispatchers.Default], never in composition: a load landing on
 *   camera idle composes dozens of new markers in one frame, and drawing their icons
 *   synchronously stuttered the appear animation right as it started. Until the icon lands (a
 *   frame or two, while the pop-in scale is still ≈0) the marker renders nothing. Already-cached
 *   icons are taken with a synchronous cache peek instead — no coroutine, no blank frame.
 * - Every icon **landing** (the write making a freshly drawn bitmap visible) costs main-thread
 *   work — a marker recomposition, a new painter, a texture upload on the RenderThread — and
 *   landings arrive in bursts. [LandingBudget] admits at most [LANDINGS_PER_FRAME] of them per
 *   frame, smearing bursts across frames instead of blowing one.
 * - The server-cluster icon is two-staged: the tint placeholder shows while Coil fetches the
 *   preview (at low quality, decoded straight down to [thumbnailTargetPx]), then the ringed
 *   thumbnail composite replaces it. The thumbnail's arrival deliberately does NOT touch the
 *   shown icon — resetting to the (pixel-identical) placeholder was a wasted extra landing per
 *   cluster in the perfetto capture.
 */
class AnnotationIconPipeline(
    private val icons: AnnotationIconFactory,
    private val imageLoader: ImageLoader,
    private val thumbnailTargetPx: Int,
) {
    private val landings = LandingBudget(LANDINGS_PER_FRAME)

    /** The image pin for the colours, upright (rotation is the renderer's graphicsLayer job). */
    @Composable
    fun pinIcon(
        tint: RgbaColor,
        shadow: RgbaColor,
    ): Bitmap? =
        icons.cachedPinBitmap(tint, shadow)
            ?: producedIcon(tint) { icons.pinBitmap(tint, shadow) }

    /** The count capsule of a local cluster. */
    @Composable
    fun bubbleIcon(
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap? =
        icons.cachedBubbleBitmap(tint, foreground, count)
            ?: producedIcon(tint, count) { icons.bubbleBitmap(tint, foreground, count) }

    /**
     * The server-cluster icon: first the tint placeholder (synchronously when already cached),
     * then the ringed thumbnail composite once Coil delivers the preview.
     */
    @Composable
    fun clusterIcon(
        cluster: ModelCluster,
        tint: RgbaColor,
        foreground: RgbaColor,
    ): Bitmap? {
        val context = LocalContext.current
        val path = cluster.preview.imagePath
        // Stage 1 — the raw preview, keyed ONLY by what affects the fetch: a tint or count change
        // must repaint the badge, not re-download the image. (produceState's value retention
        // across key changes is fine here: a different path means a different marker entirely.)
        val thumbnail by produceState<Bitmap?>(initialValue = null, path, thumbnailTargetPx) {
            value =
                withContext(Dispatchers.Default) {
                    // Async systrace span (fetch + decode happen on Coil's own dispatchers, so a
                    // synchronous section can't cover them); pairs by (name, cookie) in the trace.
                    val cookie = path.hashCode()
                    Trace.beginAsyncSection(TRACE_CLUSTER_FETCH, cookie)
                    try {
                        runCatching {
                            val request =
                                ImageRequest
                                    .Builder(context)
                                    .data(imageUrl(path, ImageQuality.Low))
                                    .size(Size(thumbnailTargetPx, thumbnailTargetPx))
                                    // Composited onto a software Canvas (serverClusterBitmap),
                                    // which can't draw hardware bitmaps — decode straight to
                                    // software instead of copying a hardware bitmap afterwards.
                                    .allowHardware(false)
                                    .build()
                            (imageLoader.execute(request) as? SuccessResult)
                                ?.image
                                ?.toBitmap()
                                ?.toSoftware()
                        }.getOrNull()
                    } finally {
                        Trace.endAsyncSection(TRACE_CLUSTER_FETCH, cookie)
                    }
                }
        }
        // Stage 2 — the shown icon. Deliberately NOT keyed by the thumbnail: its arrival must not
        // reset the state back to the pixel-identical placeholder (a wasted landing). Tint/count
        // changes DO reset it, so a stale badge is never shown; the cached-placeholder start lets
        // markers with a known (tint, count) show instantly.
        val icon =
            remember(tint, foreground, cluster.count) {
                mutableStateOf(
                    icons.cachedServerClusterPlaceholder(tint, foreground, cluster.count),
                )
            }
        LaunchedEffect(thumbnail, tint, foreground, cluster.count) {
            if (thumbnail == null && icon.value == null) {
                land(icon) { icons.serverClusterBitmap(null, tint, foreground, cluster.count) }
            }
            if (thumbnail != null) {
                land(icon) { icons.serverClusterBitmap(thumbnail, tint, foreground, cluster.count) }
            }
        }
        return icon.value
    }

    /**
     * A rasterized icon produced off the main thread and landed within the frame budget; null for
     * the frame(s) until it lands (the pop-in scale is still ≈0 then, so nothing visibly blinks).
     * [keys] restart production.
     *
     * Not produceState: it retains its value across key changes (only the producer restarts), and
     * a retained bitmap here is a stale icon — wrong tint or count — shown until the redraw lands.
     */
    @Composable
    private fun producedIcon(
        vararg keys: Any?,
        draw: () -> Bitmap,
    ): Bitmap? {
        val bitmap = remember(*keys) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(keys = keys) { land(bitmap, draw) }
        return bitmap.value
    }

    /** Draws off-main, then commits the result once the per-frame landing budget grants a slot. */
    private suspend fun land(
        state: MutableState<Bitmap?>,
        draw: () -> Bitmap,
    ) {
        val bitmap = withContext(Dispatchers.Default) { draw() }
        landings.awaitSlot()
        state.value = bitmap
    }
}

/**
 * Grants up to [perFrame] slots per frame; callers over the budget suspend to the next frame(s).
 * Main-thread confined: [awaitSlot] must be called from main-dispatched coroutines (LaunchedEffect
 * here), which also makes the plain fields safe.
 */
private class LandingBudget(
    private val perFrame: Int,
) {
    private var frameKey = Long.MIN_VALUE
    private var used = 0

    /** Suspends at least to the next frame boundary, longer whenever frames are fully booked. */
    suspend fun awaitSlot() {
        while (!withFrameMillis(::take)) {
            // This frame's budget was spent — compete again on the next one.
        }
    }

    private fun take(frameMs: Long): Boolean {
        if (frameMs != frameKey) {
            frameKey = frameMs
            used = 0
        }
        if (used == perFrame) return false
        used++
        return true
    }
}

/** A software-backed copy if this is a hardware bitmap (undrawable on a software Canvas), else itself. */
private fun Bitmap.toSoftware(): Bitmap =
    if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
