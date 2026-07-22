package com.chizberg.rewind.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException

/** Material predictive-back: the leaving surface shrinks to 90 % as the finger drags it away. */
private const val MIN_SCALE = 0.9f

/** A layer sits pressed-in behind the layer above it (rest 0.95), pushed a little further as a back
 *  gesture drags on that layer above (to 0.90), then springs back to full as it leaves. */
private const val REST_SCALE = 0.95f
private const val DRAG_SCALE = 0.90f

/** Corner radius of a pressed-in layer so it reads as a card, not a clipped rectangle. */
private val RECEDE_CORNER = 20.dp

/** How far a pressed-in layer is slid off the leading edge (a parallax under the layer above). */
private const val LEADING_HIDDEN = 0.30f

/** Peek offset of the shrinking top card toward the swiped edge, and its card look while detached. */
private val EDGE_MARGIN = 8.dp
private val MAX_CORNER = 28.dp
private val MAX_SHADOW = 6.dp

/** M3 `PredictiveBackEasing` — most of the shrink lands in the first third of the swipe. */
private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private const val ENTER_MS = 350
private const val EXIT_MS = 200
private const val CANCEL_MS = 180

/**
 * One layer in the [OverlayHost] stack. Created and owned by an [Overlay] call; the host reads its
 * animated [presence]/[back] to place it and renders its [content].
 */
@Stable
internal class OverlayEntry {
    /** True while the source `target` is non-null; drives the enter/exit of [presence] and marks the
     *  layer as a live back target. */
    var present by mutableStateOf(false)

    /** Opaque layers (the list, details, viewer) get a black scrim behind them so their receding gap
     *  reads black; the map is not opaque — its black comes from the window behind the SurfaceView. */
    var opaque by mutableStateOf(true)

    /** Set once by [Overlay]; both indirect through `rememberUpdatedState` so the latest lambda runs
     *  without re-assigning (which would recompose the host every frame). */
    var onBack: () -> Unit = {}
    var content: @Composable () -> Unit = {}

    val presence = Animatable(0f) // 0 = gone, 1 = fully up
    val back = Animatable(0f) // 0..1 interactive back-gesture progress (only meaningful while top)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
    var touchY by mutableFloatStateOf(Float.NaN)

    /** Composed while up OR animating out. A discrete flag (flipped at the animation's start and end)
     *  rather than `presence.value > 0f`, so the host recomposes on those two transitions, not on
     *  every animation frame — the smooth [presence] read stays in the graphics-layer phase. */
    var rendered by mutableStateOf(false)
}

/**
 * The ordered overlay stack. A [staticCompositionLocalOf] hands it to every [Overlay] beneath an
 * [OverlayHost]; each registers its layer here, and the host renders them bottom-to-top with the
 * receding-card motion applied automatically between neighbours.
 */
@Stable
internal class OverlayStack {
    val entries = mutableStateListOf<OverlayEntry>()

    fun add(entry: OverlayEntry) = entries.add(entry)

    fun remove(entry: OverlayEntry) = entries.remove(entry)

    /** The topmost live layer — the one that owns the back gesture. */
    fun topPresent(): OverlayEntry? = entries.lastOrNull { it.present }

    /** The layer directly on top of [index] (`-1` = the base): the lowest *rendered* entry above it.
     *  Skipping non-rendered entries lets two mutually-exclusive siblings over one parent (the pin
     *  details and the list both sit over the map) each recede it correctly. */
    fun childOf(index: Int): OverlayEntry? {
        for (i in (index + 1) until entries.size) {
            if (entries[i].rendered) return entries[i]
        }
        return null
    }
}

internal val LocalOverlayStack =
    staticCompositionLocalOf<OverlayStack> { error("Overlay used outside an OverlayHost") }

/**
 * Hosts the whole state-driven overlay stack over a persistent [base] (the map). Replaces the older
 * per-screen `OverlayScreen`: instead of each overlay opting into a `background` slot it can forget,
 * any screen anywhere under here calls [Overlay] and is stacked automatically — the layer beneath it
 * recedes, an opaque layer gets its black backing, and the top layer owns the system back gesture.
 * Nesting is free: an [Overlay] inside another overlay's content just registers one layer higher.
 *
 * The motion mirrors the system's own screens (Settings): the two neighbouring layers move as a pair
 * — the lower recedes to [REST_SCALE] behind the upper and grows back as it leaves — so there is
 * always a sense of one screen behind another rather than a cross-fade. Below API 34 the platform
 * sends no gesture progress; the flow just completes on a back press and the exit animation plays.
 */
@Composable
fun OverlayHost(
    base: @Composable () -> Unit,
    overlays: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stack = remember { OverlayStack() }

    CompositionLocalProvider(LocalOverlayStack provides stack) {
        Box(modifier.fillMaxSize()) {
            // The base (map) recedes behind whatever sits lowest in the stack; it is not opaque, so
            // it has no Compose scrim — its gap shows the black window behind the map's SurfaceView.
            Box(Modifier.fillMaxSize().recede(stack.childOf(-1))) { base() }

            stack.entries.forEachIndexed { index, entry ->
                if (!entry.rendered) return@forEachIndexed
                // The whole layer slides/scales for its own enter/exit and back-drag; inside it, a
                // black scrim (opaque layers only) stays put behind the content, which itself recedes
                // when a deeper layer opens over it — so the receding content's gap reads black.
                Box(Modifier.fillMaxSize().enterExit(entry)) {
                    if (entry.opaque) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                    Box(Modifier.fillMaxSize().recede(stack.childOf(index))) { entry.content() }
                }
            }

            // Registers every layer into [stack] and renders nothing itself; kept inside the provider
            // so nested Overlay calls (in an overlay's own content, composed above) see the stack too.
            overlays()

            // One predictive-back handler, always for the current top layer. It registers after the
            // activity's root callback, so LIFO hands it the gesture; with no layer up, the root
            // callback (minimise to launcher) runs instead.
            val top = stack.topPresent()
            if (top != null) {
                key(top) {
                    PredictiveBackHandler { events ->
                        try {
                            events.collect { event ->
                                top.edge = event.swipeEdge
                                top.touchY = event.touchY
                                top.back.snapTo(PredictiveBackEasing.transform(event.progress))
                            }
                            top.onBack()
                            // Abandoned mid-swipe — cancellation is the signal, not a failure.
                        } catch (expected: CancellationException) {
                            top.back.animateTo(0f, tween(CANCEL_MS))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Presents [content] as a layer in the enclosing [OverlayHost] while [target] is non-null. The
 * receding of the layer below, the black backing for [opaque] layers, and the back gesture are all
 * the host's job — a caller only says "here is my overlay and how to dismiss it".
 *
 * @param target non-null means this layer is up; clear it from [onBack].
 * @param opaque whether this layer paints a full-screen surface (so it needs a black backing when it
 *   recedes). The map passes `false`; every real screen leaves the default `true`.
 */
@Composable
fun <T : Any> Overlay(
    target: T?,
    onBack: () -> Unit,
    opaque: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    val stack = LocalOverlayStack.current
    val entry = remember { OverlayEntry() }

    // Outlives a null target for the length of the exit animation, so the layer keeps rendering the
    // last value as it slides out.
    var shown by remember { mutableStateOf<T?>(null) }
    if (target != null) shown = target

    val currentContent by rememberUpdatedState(content)
    val currentOnBack by rememberUpdatedState(onBack)
    remember(entry) {
        entry.content = { shown?.let { currentContent(it) } }
        entry.onBack = { currentOnBack() }
        true
    }
    // Equal writes to these are de-duplicated by snapshot state, so this does not churn the host.
    SideEffect {
        entry.present = target != null
        entry.opaque = opaque
    }

    DisposableEffect(stack) {
        stack.add(entry)
        onDispose { stack.remove(entry) }
    }

    LaunchedEffect(entry, target != null) {
        if (target != null) {
            entry.rendered = true
            entry.presence.animateTo(1f, tween(ENTER_MS, easing = EmphasizedDecelerate))
        } else if (entry.rendered) {
            entry.presence.animateTo(0f, tween(EXIT_MS, easing = EaseInOut))
            entry.rendered = false
            entry.back.snapTo(0f)
            entry.touchY = Float.NaN
        }
    }
}

/** The pressed-in transform a layer wears while [child] (the layer directly above it) is up: scaled
 *  back, slid off the leading edge, rounded — driven by that child's presence and its back-drag. */
private fun Modifier.recede(child: OverlayEntry?): Modifier =
    graphicsLayer {
        val open = child?.presence?.value ?: 0f
        if (open <= 0f) return@graphicsLayer
        val dragged = child?.back?.value ?: 0f
        val scale = lerp(1f, lerp(REST_SCALE, DRAG_SCALE, dragged), open)
        scaleX = scale
        scaleY = scale
        translationX = -LEADING_HIDDEN * size.width * open
        shape = RoundedCornerShape(RECEDE_CORNER * open)
        clip = true
    }

/** The transform a layer wears for its own arrival/exit and its back-drag: an opaque slide off the
 *  leading edge that shrinks toward the finger — never a fade. [OverlayEntry.back] is non-zero only
 *  for the layer being dragged and holds its committed value through the exit, so no `isTop` gate is
 *  needed — reading it raw keeps the commit smooth (no one-frame jump back to full size). */
private fun Modifier.enterExit(entry: OverlayEntry): Modifier =
    graphicsLayer {
        val dragged = entry.back.value
        val leaving = 1f - entry.presence.value
        val fromLeft = entry.edge == BackEventCompat.EDGE_LEFT
        val cardness = maxOf(dragged, leaving)

        val scale = lerp(1f, MIN_SCALE, cardness)
        scaleX = scale
        scaleY = scale
        transformOrigin =
            TransformOrigin(
                pivotFractionX = lerp(0.5f, if (fromLeft) 1f else 0f, dragged),
                pivotFractionY = lerp(0.5f, touchFraction(entry.touchY, size.height), dragged),
            )
        translationX =
            (if (fromLeft) -1f else 1f) * EDGE_MARGIN.toPx() * dragged + size.width * leaving
        shape = RoundedCornerShape(MAX_CORNER * cardness)
        shadowElevation = MAX_SHADOW.toPx() * cardness
        clip = cardness > 0f
    }

/** Where the finger sits down the screen, as a fraction; centred until the platform tells us. */
private fun touchFraction(
    touchY: Float,
    height: Float,
): Float = if (touchY.isNaN() || height <= 0f) 0.5f else (touchY / height).coerceIn(0f, 1f)
