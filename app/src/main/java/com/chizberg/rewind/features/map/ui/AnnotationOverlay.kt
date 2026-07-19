package com.chizberg.rewind.features.map.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tracing.trace
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.Mercator
import com.chizberg.rewind.features.map.coordinate
import com.google.maps.android.compose.CameraPositionState
import kotlin.math.round
import kotlin.math.roundToInt

// Compose (and pay for) only annotations within this multiple of the visible span. The region is
// the one from the last camera idle — stale during a fling — so the extra half-viewport per side
// covers what a pan can reveal before the next idle reprojects and reloads.
private const val CULL_SPAN_MULTIPLIER = 2.0

/**
 * The annotation layer, drawn as real Composables **on top of** the [GoogleMap] surface (a sibling,
 * not map markers). Google Maps markers are GPU bitmaps the SDK can only fade/rotate, never scale;
 * to reproduce iOS's MKAnnotationView scale-pop (0.01→1) we place each annotation ourselves and
 * animate its `graphicsLayer` scale (see AnnotationAnimator.kt). Icon bitmaps are produced and
 * delivered by [AnnotationIconPipeline]. Both are Android-only performance infrastructure kept out
 * of this file so it stays structurally close to the iOS annotation views.
 *
 * Placement is one parent [Layout] whose placement block re-runs on every camera move, reading
 * [CameraPositionState.position] **once** and positioning all children with pure [Mercator] math —
 * no `Projection`/`toScreenLocation` (each is a Play-services round-trip plus allocations, and
 * per-marker-per-frame they were the audited cause of pan jank). Exact while rotation and tilt are
 * disabled. On fast flings a ~1-frame lag between the map surface and the overlay is possible;
 * acceptable trade for parity animations.
 *
 * Only annotations inside [region] ×[CULL_SPAN_MULTIPLIER] are composed at all — off-screen ones
 * must not pay composition, placement, or animation costs (state itself is bounded separately by
 * eviction in LocalClustering).
 *
 * Enter/exit: [rememberAnnotationPresence] keeps a departed annotation composed until its shrink
 * finishes (Compose would otherwise drop it instantly, killing the exit animation). Identity is by
 * [AnnotationValue] key (image cid / server cluster cid / local cluster id) so annotations surviving
 * a reload keep their view and do **not** re-animate — mirroring iOS reusing views by identity.
 */
@Composable
fun AnnotationOverlay(
    annotations: List<AnnotationValue>,
    region: Region,
    cameraPositionState: CameraPositionState,
    scheme: GradientScheme,
    maxRange: IntRange,
    iconPipeline: AnnotationIconPipeline,
    modifier: Modifier = Modifier,
    // The map's bottom content padding (the preview strip's reserved height). Google Maps draws the
    // camera target at the center of the *padded* viewport — B/2 above the geometric center — so the
    // overlay must shift its own center up by the same amount, or every marker sits B/2 too low.
    contentPaddingBottom: Dp = 0.dp,
) {
    val keep = remember(region) { region.expanded(CULL_SPAN_MULTIPLIER) }
    val visible =
        remember(annotations, keep) {
            annotations.filter { keep.contains(it.coordinate()) }
        }
    val presence = rememberAnnotationPresence(visible, keep)
    Layout(
        content = {
            presence.entries.forEach { entry ->
                key(entry.key) {
                    AnnotationMarker(
                        entry = entry,
                        frameTimeMs = presence.frameTimeMs,
                        scheme = scheme,
                        maxRange = maxRange,
                        iconPipeline = iconPipeline,
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        // Screen point where the map draws the camera target: horizontally centered, but raised by
        // half the bottom padding to match Google Maps' padded-viewport center.
        val centerX = constraints.maxWidth / 2f
        val centerY = (constraints.maxHeight - contentPaddingBottom.toPx()) / 2f
        layout(constraints.maxWidth, constraints.maxHeight) {
            trace("Rewind:place") {
                // The overlay's single per-frame camera read: placement re-runs on every camera
                // move, but touches no Play services — pure math, no allocations.
                val camera = cameraPositionState.position
                val zoom = camera.zoom.toDouble()
                val world = Mercator.worldSize(zoom) * density
                val camX = Mercator.worldX(camera.target.longitude, zoom) * density
                val camY = Mercator.worldY(camera.target.latitude, zoom) * density
                placeables.forEachIndexed { index, placeable ->
                    val coordinate =
                        measurables[index].layoutId as? Coordinate ?: return@forEachIndexed
                    // Wrap the x-delta to the nearest world copy so markers just across the
                    // antimeridian sit beside the camera, not a full world width away.
                    var dx = Mercator.worldX(coordinate.longitude, zoom) * density - camX
                    dx -= round(dx / world) * world
                    val dy = Mercator.worldY(coordinate.latitude, zoom) * density - camY
                    // Centre the view on the point (iOS anchors views on the coordinate).
                    placeable.place(
                        x = (centerX + dx).roundToInt() - placeable.width / 2,
                        y = (centerY + dy).roundToInt() - placeable.height / 2,
                    )
                }
            }
        }
    }
}

/**
 * A single scale-animated annotation view. Its coordinate travels to the parent [Layout] via
 * [Modifier.layoutId]; the parent owns all positioning, the animator owns the scale.
 */
@Composable
private fun AnnotationMarker(
    entry: PresenceEntry,
    frameTimeMs: LongState,
    scheme: GradientScheme,
    maxRange: IntRange,
    iconPipeline: AnnotationIconPipeline,
) {
    val annotation = entry.value
    // No pointer input here — the marker must be touch-transparent. A `pointerInput` on this
    // Compose overlay (a sibling *above* the GoogleMap AndroidView) swallows the gesture stream
    // before the map's SurfaceView sees it, so a swipe begun on a marker can never pan the map —
    // and no amount of "don't consume the down" hands a mid-gesture drag back to the interop view.
    // Taps are instead resolved by the map itself via `onMapClick` (see RewindMap.pickAnnotation),
    // which the SDK fires only for a tap, never a drag — so drags reach the map and pan cleanly.
    Box(
        Modifier
            .layoutId(annotation.coordinate())
            .presenceScale(entry, frameTimeMs),
    ) {
        AnnotationContent(annotation, scheme, maxRange, iconPipeline)
    }
}

/**
 * What each annotation kind looks like — the counterpart of iOS's three annotation views
 * (Image/Merged/Cluster). Resolves the year tint, asks [iconPipeline] for the bitmap (1:1 pixel
 * size), and shows it; direction is a GPU-layer rotation, not baked into the bitmap (one cached
 * upright pin per tint serves every angle — baking multiplied cache keys ×360).
 */
@Composable
private fun AnnotationContent(
    annotation: AnnotationValue,
    scheme: GradientScheme,
    maxRange: IntRange,
    iconPipeline: AnnotationIconPipeline,
) {
    when (annotation) {
        is AnnotationValue.Image -> {
            val image = annotation.value
            val tint = scheme.color(image.date.year, maxRange)
            val foreground = scheme.foreground(tint)
            val angle = image.dir?.angleDegrees ?: 0f
            iconPipeline.pinIcon(tint, foreground)?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = image.title,
                    modifier = if (angle == 0f) Modifier else Modifier.rotate(angle),
                )
            }
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
            val foreground = scheme.foreground(tint)
            iconPipeline.bubbleIcon(tint, foreground, cluster.images.size)?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null)
            }
        }

        is AnnotationValue.Cluster -> {
            val cluster = annotation.value
            val tint = scheme.color(cluster.preview.date.year, maxRange)
            val foreground = scheme.foreground(tint)
            iconPipeline.clusterIcon(cluster, tint, foreground)?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null)
            }
        }
    }
}
