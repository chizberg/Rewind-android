package com.chizberg.rewind.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException

/** Material predictive-back: the leaving surface shrinks to 90 % as the finger drags it away. */
private const val MIN_SCALE = 0.9f

/** The background (map) sits pressed-in behind an open overlay, and is pushed a little further as
 *  the back gesture drags — then springs to full-screen (1.0) once the overlay commits away. */
private const val BACKGROUND_REST_SCALE = 0.95f
private const val BACKGROUND_DRAG_SCALE = 0.90f

/** Corner radius of the pressed-in background so it reads as a card, not a clipped rectangle. */
private val BACKGROUND_CORNER = 20.dp

/** How far the pressed-in background is slid off the leading edge (a parallax under the overlay). */
private const val BACKGROUND_LEADING_HIDDEN = 0.30f

/** Peek offset of the shrinking card toward the swiped edge, and its card look while detached. */
private val EDGE_MARGIN = 8.dp
private val MAX_CORNER = 28.dp
private val MAX_SHADOW = 6.dp

/** M3 `PredictiveBackEasing` — most of the shrink lands in the first third of the swipe. */
private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Ease-in-out for the committed exit: the pair eases apart, then eases back together. */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private const val ENTER_MS = 350
private const val EXIT_MS = 200
private const val CANCEL_MS = 180

/**
 * The container for every state-driven overlay in the app (image details, and later the image list,
 * search, settings, comparison). It hosts a persistent [background] with the overlay layered on top,
 * and owns the whole presentation, modelled on the system's own screens (Settings): as the overlay
 * comes and goes, the two layers move as a pair — the background recedes to [BACKGROUND_MIN_SCALE]
 * behind the overlay and grows back to full as it leaves, so there is always a sense of one screen
 * sitting behind another rather than a cross-fade.
 *
 * - **appear** — the overlay slides up opaque from the bottom as [target] turns non-null, while the
 *   background recedes.
 * - **system back gesture** — [PredictiveBackHandler] registers an `OnBackAnimationCallback`, and the
 *   platform streams `BackEventCompat` (progress, touch position, swiped edge) as the finger moves.
 *   The overlay becomes a rounded card shrinking toward the swiped edge; the background, revealed
 *   around it, is scaled down to say "not there yet" and grows to full only as the gesture commits.
 *   We never touch pointer input — the gesture is the platform's; only progress → transform is ours.
 * - **leave** — every dismissal path animates out (the overlay is kept composed after [target] goes
 *   null until the exit finishes). Nothing cross-fades: the leaving overlay stays opaque and slides
 *   off, so there is never a blurry half-and-half frame.
 *
 * Nesting works by itself: an inner `OverlayScreen` composes after its host, so its back callback is
 * registered later and the dispatcher — being LIFO — hands it the gesture first. (A nested overlay
 * passes no [background]; it lets the parent show through unscaled.)
 *
 * Use this INSTEAD of `BackHandler`, never alongside it: a bare `BackHandler` also claims the gesture
 * (so the system stops drawing its own preview) but discards the progress, leaving the swipe dead.
 *
 * Below API 34 the platform sends no progress events — the flow just completes on a back press and
 * the exit animation plays. Nothing to branch on.
 *
 * @param target the state that drives presentation; non-null means "this overlay is up".
 * @param onBack invoked when the user commits a back gesture — clear [target] in response.
 * @param background the always-composed layer beneath the overlay (e.g. the map). Never disposed.
 * @param content the overlay itself, receiving the [target] it was presented with (it keeps
 *   rendering the last value while animating out).
 */
@Composable
fun <T : Any> OverlayScreen(
    target: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    background: @Composable () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    // What is on screen right now — outlives `target` for the length of the exit animation.
    var shown by remember { mutableStateOf<T?>(null) }
    val presence = remember { Animatable(0f) } // 0 = overlay closed, 1 = fully open
    val back = remember { Animatable(0f) } // 0..1 interactive back-gesture progress
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var touchY by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(target) {
        if (target != null) {
            shown = target
            presence.animateTo(1f, tween(ENTER_MS, easing = EmphasizedDecelerate))
        } else if (shown != null) {
            presence.animateTo(0f, tween(EXIT_MS, easing = EaseInOut))
            shown = null
            back.snapTo(0f)
            touchY = Float.NaN
        }
    }

    Box(modifier.fillMaxSize()) {
        // The background is pressed in behind an open overlay (rest 0.95) and pushed a little
        // further as the back gesture drags (to 0.90) — both layers shrink together, "with
        // resistance" via the eased `back`. It springs back to full-screen only as the overlay
        // commits away (presence → 0). Rounded while inset so it reads as a card behind a card.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val open = presence.value
                    val inset = lerp(BACKGROUND_REST_SCALE, BACKGROUND_DRAG_SCALE, back.value)
                    val scale = lerp(1f, inset, open)
                    scaleX = scale
                    scaleY = scale
                    // Slid off the leading (left) edge behind the overlay, back to centre as it
                    // fills — the previous screen parallaxing in from behind the leaving one.
                    translationX = -BACKGROUND_LEADING_HIDDEN * size.width * open
                    shape = RoundedCornerShape(BACKGROUND_CORNER * open)
                    clip = open > 0f
                },
        ) {
            background()
        }

        // Take `target` directly rather than the effect-assigned `shown`: `shown` lands a frame
        // late, and in that gap the overlay would be up with nobody claiming back — a swipe there
        // would pop the layer underneath us. `shown` still outlives a null `target` for the exit.
        val current = target ?: shown
        if (current != null) {
            PredictiveBackHandler { events ->
                try {
                    events.collect { event ->
                        edge = event.swipeEdge
                        touchY = event.touchY
                        back.snapTo(PredictiveBackEasing.transform(event.progress))
                    }
                    // Released past the threshold (or a plain back press): hand over, then `target`
                    // goes null and the effect above carries the card on out from exactly here.
                    onBack()
                    // Abandoned mid-swipe — cancellation is the documented signal, not a failure.
                } catch (expected: CancellationException) {
                    back.animateTo(0f, tween(CANCEL_MS))
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val dragged = back.value
                        val leaving = 1f - presence.value
                        val fromLeft = edge == BackEventCompat.EDGE_LEFT

                        // Enter/exit: an opaque slide off the right edge (back convention), full
                        // width so it clears without a pop — never a fade.
                        val scale = lerp(1f, MIN_SCALE, maxOf(dragged, leaving))
                        scaleX = scale
                        scaleY = scale
                        // While dragging, pivot on the edge opposite the swipe so the card shrinks
                        // away from the finger; while sliding, pivot centre.
                        transformOrigin =
                            TransformOrigin(
                                pivotFractionX = lerp(0.5f, if (fromLeft) 1f else 0f, dragged),
                                pivotFractionY =
                                    lerp(
                                        0.5f,
                                        touchFraction(touchY, size.height),
                                        dragged,
                                    ),
                            )
                        val edgePeek = (if (fromLeft) -1f else 1f) * EDGE_MARGIN.toPx() * dragged
                        translationX = edgePeek + size.width * leaving

                        val cardness = maxOf(dragged, leaving)
                        shape = RoundedCornerShape(MAX_CORNER * cardness)
                        shadowElevation = MAX_SHADOW.toPx() * cardness
                        clip = cardness > 0f
                    },
            ) {
                content(current)
            }
        }
    }
}

/** Where the finger sits down the screen, as a fraction; centred until the platform tells us. */
private fun touchFraction(
    touchY: Float,
    height: Float,
): Float = if (touchY.isNaN() || height <= 0f) 0.5f else (touchY / height).coerceIn(0f, 1f)
