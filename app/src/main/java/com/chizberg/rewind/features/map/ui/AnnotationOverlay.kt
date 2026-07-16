package com.chizberg.rewind.features.map.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.network.ImageQuality
import com.chizberg.rewind.network.imageUrl
import com.google.maps.android.compose.CameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// iOS AnnotationAnimator: scale 0.01 <-> 1 over 0.2s. The helper animates with UIKit's default
// `options: []` curve, i.e. easeInOut — CubicBezier(0.42, 0, 0.58, 1) is its exact match.
private const val SUPER_SMALL = 0.01f
private const val ANIM_DURATION_MS = 200
private val EaseInOutCubic = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

// Where to park a marker for the frame(s) before the projection is ready (startup only).
private const val OFFSCREEN = -100_000

/**
 * The annotation layer, drawn as real Composables **on top of** the [GoogleMap] surface (a sibling,
 * not map markers). Google Maps markers are GPU bitmaps the SDK can only fade/rotate, never scale;
 * to reproduce iOS's MKAnnotationView scale-pop (0.01→1) we project each annotation's coordinate to
 * a screen point via [CameraPositionState.projection] and place a Composable there, then animate its
 * `graphicsLayer` scale. Scaling is GPU-composited (no per-frame bitmap re-rasterization).
 *
 * Divergence / cost: annotations are no longer pinned by the SDK — the [Modifier.offset] re-projects
 * on every camera move (reading [CameraPositionState.position]). On fast flings a ~1-frame lag
 * between the map surface and the overlay is possible; acceptable trade for parity animations, and
 * the appear/remove animation itself only runs while the camera is idle (data loads on idle).
 *
 * Enter/exit: [rememberAnnotationPresence] keeps a departed annotation composed until its shrink
 * finishes (Compose would otherwise drop it instantly, killing the exit animation). Identity is by
 * [AnnotationValue] key (image cid / server cluster cid / local cluster id) so annotations surviving
 * a reload keep their view and do **not** re-animate — mirroring iOS reusing views by identity.
 */
@Composable
fun AnnotationOverlay(
    annotations: List<AnnotationValue>,
    cameraPositionState: CameraPositionState,
    scheme: GradientScheme,
    maxRange: IntRange,
    icons: AnnotationIconFactory,
    imageLoader: ImageLoader,
    thumbnailTargetPx: Int,
    modifier: Modifier = Modifier,
) {
    val presence = rememberAnnotationPresence(annotations)
    Box(modifier.fillMaxSize()) {
        presence.forEach { entry ->
            key(entry.key) {
                AnnotationMarker(
                    entry = entry,
                    cameraPositionState = cameraPositionState,
                    scheme = scheme,
                    maxRange = maxRange,
                    icons = icons,
                    imageLoader = imageLoader,
                    thumbnailTargetPx = thumbnailTargetPx,
                    onExited = { presence.remove(entry) },
                )
            }
        }
    }
}

/** One tracked annotation view: its stable [key], the latest model, and whether it is shrinking out. */
private class PresenceEntry(
    val key: String,
    value: AnnotationValue,
    exiting: Boolean,
) {
    var value by mutableStateOf(value)
    var exiting by mutableStateOf(exiting)
}

/**
 * Reconciles [annotations] into a mutable list that also holds annotations that have left the source
 * but are still animating out. New keys are appended (they enter from [SUPER_SMALL]); vanished keys
 * are flagged `exiting` (the marker removes itself once its shrink completes); a key that reappears
 * mid-exit is un-flagged and grows back.
 */
@Composable
private fun rememberAnnotationPresence(annotations: List<AnnotationValue>): SnapshotList {
    val entries = remember { mutableStateListOf<PresenceEntry>() }
    LaunchedEffect(annotations) {
        val incoming = annotations.associateBy { it.key() }
        incoming.forEach { (key, value) ->
            val existing = entries.firstOrNull { it.key == key }
            if (existing == null) {
                entries.add(PresenceEntry(key, value, exiting = false))
            } else {
                existing.value = value
                existing.exiting = false
            }
        }
        entries.forEach { entry ->
            if (!incoming.containsKey(entry.key)) entry.exiting = true
        }
    }
    return entries
}

private typealias SnapshotList = androidx.compose.runtime.snapshots.SnapshotStateList<PresenceEntry>

/** A single projected, scale-animated annotation view. */
@Composable
private fun AnnotationMarker(
    entry: PresenceEntry,
    cameraPositionState: CameraPositionState,
    scheme: GradientScheme,
    maxRange: IntRange,
    icons: AnnotationIconFactory,
    imageLoader: ImageLoader,
    thumbnailTargetPx: Int,
    onExited: () -> Unit,
) {
    val annotation = entry.value
    val latLng = remember(annotation) { annotation.coordinate().toLatLng() }

    val scale = remember { Animatable(SUPER_SMALL) }
    LaunchedEffect(entry.exiting) {
        if (entry.exiting) {
            scale.animateTo(SUPER_SMALL, tween(ANIM_DURATION_MS, easing = EaseInOutCubic))
            onExited()
        } else {
            scale.animateTo(1f, tween(ANIM_DURATION_MS, easing = EaseInOutCubic))
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .onSizeChanged { size = it }
            .offset {
                // Reading the camera position re-runs placement on every camera move; the
                // projection derives from it but isn't itself observable, so observe position.
                val point =
                    cameraPositionState.position.let {
                        cameraPositionState.projection?.toScreenLocation(latLng)
                    }
                if (point == null) {
                    IntOffset(OFFSCREEN, OFFSCREEN)
                } else {
                    // Centre the view on the point (iOS anchors annotation views on the coordinate).
                    IntOffset(point.x - size.width / 2, point.y - size.height / 2)
                }
            }
            // Scale is applied after layout, so it never changes the measured [size] used for
            // centring; graphicsLayer's default origin is the (already-centred) view centre.
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    ) {
        AnnotationContent(annotation, scheme, maxRange, icons, imageLoader, thumbnailTargetPx)
    }
}

/** Draws the tinted bitmap for [annotation] (reusing [AnnotationIconFactory]); 1:1 pixel size. */
@Composable
private fun AnnotationContent(
    annotation: AnnotationValue,
    scheme: GradientScheme,
    maxRange: IntRange,
    icons: AnnotationIconFactory,
    imageLoader: ImageLoader,
    thumbnailTargetPx: Int,
) {
    when (annotation) {
        is AnnotationValue.Image -> {
            val image = annotation.value
            val tint = scheme.color(image.date.year, maxRange)
            val angle = image.dir?.angleDegrees ?: 0f
            val bitmap =
                remember(tint, angle) { icons.pinBitmap(tint, scheme.foreground(tint), angle) }
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = image.title)
        }

        is AnnotationValue.LocalCluster -> {
            val cluster = annotation.value
            val tint =
                scheme.color(
                    cluster.images
                        .first()
                        .date.year,
                    maxRange,
                )
            val count = cluster.images.size
            val bitmap =
                remember(tint, count) {
                    icons.bubbleBitmap(tint, scheme.foreground(tint), count)
                }
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
        }

        is AnnotationValue.Cluster -> {
            val cluster = annotation.value
            val tint = scheme.color(cluster.preview.date.year, maxRange)
            val foreground = scheme.foreground(tint)
            val thumbnail =
                rememberThumbnail(imageLoader, cluster.preview.imagePath, thumbnailTargetPx)
            val bitmap =
                remember(tint, foreground, cluster.count, thumbnail) {
                    icons.serverClusterBitmap(thumbnail, tint, foreground, cluster.count)
                }
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
        }
    }
}

private fun AnnotationValue.coordinate(): Coordinate =
    when (this) {
        is AnnotationValue.Image -> value.coordinate
        is AnnotationValue.Cluster -> value.coordinate
        is AnnotationValue.LocalCluster -> value.coordinate
    }

/** Stable per-annotation identity for Compose keying and presence tracking (mirrors iOS by-id reuse). */
private fun AnnotationValue.key(): String =
    when (this) {
        is AnnotationValue.Image -> "i:${value.cid}"
        is AnnotationValue.Cluster -> "c:${value.preview.cid}"
        is AnnotationValue.LocalCluster -> "lc:${value.id}"
    }

/**
 * Loads a cluster preview at low (`s`) quality via Coil, decoded down to [targetPx] (it's only
 * shown at 60dp); `null` until it arrives (or on failure). The decode + software copy run off the
 * main thread so a burst of cluster loads doesn't stall panning.
 */
@Composable
private fun rememberThumbnail(
    imageLoader: ImageLoader,
    path: String,
    targetPx: Int,
): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, path, targetPx) {
        val url = imageUrl(path, ImageQuality.Low)
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(Size(targetPx, targetPx))
                            .build()
                    // Composited onto a software Canvas, which can't draw a hardware bitmap — force
                    // a software copy if Coil handed one back.
                    (imageLoader.execute(request) as? SuccessResult)
                        ?.image
                        ?.toBitmap()
                        ?.toSoftware()
                }.getOrNull()
            }
    }
    return bitmap
}

/** A software-backed copy if this is a hardware bitmap (undrawable on a software Canvas), else itself. */
private fun Bitmap.toSoftware(): Bitmap =
    if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
