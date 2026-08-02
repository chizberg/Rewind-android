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
import androidx.compose.material3.MaterialTheme
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
private val RECEDE_CORNER = 28.dp

/** How far a pressed-in layer is slid off the leading edge (a parallax under the layer above). */
private const val LEADING_HIDDEN = 0.30f

/** Peek offset of the shrinking top card toward the swiped edge. */
private val EDGE_MARGIN = 8.dp

/**
 * How round and how lifted a layer gets while it is in motion, both of them at their strongest when
 * the layer is fully detached (arriving, leaving, or dragged) and gone by the time it settles. The
 * corners are generous on purpose — a moving screen should read as a card over the one behind it,
 * and at the device's own corner radius it reads instead as the screen itself.
 */
private val MAX_CORNER = 36.dp
private val MAX_SHADOW = 28.dp

/**
 * How far into a layer's travel it is already fully detached. Tied to the raw progress, the corner
 * and the shadow are at their strongest while the layer is still off-screen and have all but faded
 * by the time it crosses the display — the one stretch where they can actually be read. Held at full
 * instead until the last third of the arrival, where the card drops them as it docks.
 */
private const val DETACHED_AT = 0.35f

/** The pressed-in layer's own lift. Well under the moving card's — it is the one being covered, and
 *  all that shows of it is a band at the top and bottom of the screen. */
private val RECEDE_SHADOW = 16.dp

/**
 * The shadows are meant to be felt, not seen: they say which surface is on top and steer the eye
 * toward the arriving one, and a transition where the user could point at a shadow would have
 * spent attention on chrome instead. Elevation alone cannot say that — it buys width and darkness
 * together, and the width is the part that makes a shadow soft — so the tint takes the darkness
 * back out. A large [MAX_SHADOW] therefore spreads the gradient over a wide band, and this alpha
 * keeps its deepest point (right at the card's edge, where the platform's own spot shadow is
 * strongest) down to a few percent, thinning to nothing well before the band ends.
 *
 * Measured on the emulator rather than guessed, because the platform's own two lights are far
 * apart: the spot alpha is 0.19 and the ambient one 0.039, and a card's *leading* edge — the edge
 * the eye follows during a slide — is lit almost entirely by the ambient light. So this multiplier
 * lands the leading edge around 2 % darker than the background and the band under a pressed-in
 * card's top edge around 3.5 %. Below roughly 1.5 % a gradient that wide stops registering at all.
 */
private val ShadowTint = Color.Black.copy(alpha = 0.55f)

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

    /** Opaque layers (the list, details, viewer) get a scrim behind them so their receding gap reads
     *  as the app's background; the map is not opaque — it is backed from below instead, since
     *  anything painted over it would seal its SurfaceView's hole punch. */
    var opaque by mutableStateOf(true)

    /** Set once by [Overlay]; both indirect through `rememberUpdatedState` so the latest lambda runs
     *  without re-assigning (which would recompose the host every frame). */
    var onBack: () -> Unit = {}
    var content: @Composable () -> Unit = {}

    /** False for a layer with no way out but its own content (the onboarding): the host then leaves
     *  the gesture to the activity, which minimises the app instead of dismissing anything. */
    var acceptsBack by mutableStateOf(true)

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
 * recedes, an opaque layer gets its backing, and the top layer owns the system back gesture.
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
    // What shows through wherever a layer has moved out of the way: the app's own background, so a
    // transition reads as one app moving between its screens instead of a window opening onto the
    // system. Every gap in the stack is painted with it — the scrim of an opaque layer below, and
    // the backdrop below the map.
    val backdrop = MaterialTheme.colorScheme.background

    CompositionLocalProvider(LocalOverlayStack provides stack) {
        Box(modifier.fillMaxSize()) {
            // The base (map) has no scrim of its own: it is not opaque, and a Compose background
            // ABOVE it would seal the hole its SurfaceView punches through the window and black out
            // the whole composite (see themes.xml). Painted BELOW it instead — the punch clears the
            // window buffer within the map's own bounds, so this only ever shows in the gap the
            // pressed-in map leaves. Composed only while something is up over the map, so the map
            // alone never pays for a full-screen fill it would cover anyway.
            if (stack.childOf(-1) != null) {
                Box(Modifier.fillMaxSize().background(backdrop))
            }
            Box(Modifier.fillMaxSize().recede(stack.childOf(-1))) { base() }

            stack.entries.forEachIndexed { index, entry ->
                if (!entry.rendered) return@forEachIndexed
                // The whole layer slides/scales for its own enter/exit and back-drag; inside it, a
                // scrim (opaque layers only) stays put behind the content, which itself recedes
                // when a deeper layer opens over it — so the receding content's gap reads as the
                // app's background rather than as a hole in the layer.
                Box(Modifier.fillMaxSize().enterExit(entry)) {
                    if (entry.opaque) {
                        Box(Modifier.fillMaxSize().background(backdrop))
                    }
                    Box(Modifier.fillMaxSize().recede(stack.childOf(index))) { entry.content() }
                }
            }

            // Registers every layer into [stack] and renders nothing itself; kept inside the provider
            // so nested Overlay calls (in an overlay's own content, composed above) see the stack too.
            overlays()

            // One predictive-back handler, always for the current top layer. It registers after the
            // activity's root callback, so LIFO hands it the gesture; with no layer up, the root
            // callback (minimise to launcher) runs instead — and so it does for a top layer that
            // does not accept back at all: the gesture is neither consumed here nor passed down to
            // a layer buried underneath, it just leaves the app with the stack intact.
            val top = stack.topPresent()
            if (top != null && top.acceptsBack) {
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
 * receding of the layer below, the backing for [opaque] layers, and the back gesture are all the
 * host's job — a caller only says "here is my overlay and how to dismiss it".
 *
 * @param target non-null means this layer is up; clear it from [onBack].
 * @param onBack how the back gesture dismisses this layer, or `null` if it cannot be backed out of
 *   at all (the onboarding). A null handler is not the same as an empty one: the layer stops being a
 *   back target entirely, so the gesture never grabs it and leaves it sitting half-dragged.
 * @param opaque whether this layer paints a full-screen surface (so it needs a backing when it
 *   recedes). The map passes `false`; every real screen leaves the default `true`.
 */
@Composable
fun <T : Any> Overlay(
    target: T?,
    onBack: (() -> Unit)?,
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
        entry.onBack = { currentOnBack?.invoke() }
        true
    }
    // Equal writes to these are de-duplicated by snapshot state, so this does not churn the host.
    SideEffect {
        entry.present = target != null
        entry.opaque = opaque
        entry.acceptsBack = onBack != null
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
 *  back, slid off the leading edge, rounded and lifted off the backdrop — driven by that child's
 *  presence and its back-drag. */
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
        shadowElevation = RECEDE_SHADOW.toPx() * open
        ambientShadowColor = ShadowTint
        spotShadowColor = ShadowTint
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
        val detached = (cardness / DETACHED_AT).coerceAtMost(1f)

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
        shape = RoundedCornerShape(MAX_CORNER * detached)
        shadowElevation = MAX_SHADOW.toPx() * detached
        ambientShadowColor = ShadowTint
        spotShadowColor = ShadowTint
        clip = cardness > 0f
    }

/** Where the finger sits down the screen, as a fraction; centred until the platform tells us. */
private fun touchFraction(
    touchY: Float,
    height: Float,
): Float = if (touchY.isNaN() || height <= 0f) 0.5f else (touchY / height).coerceIn(0f, 1f)
