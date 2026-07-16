/*
 * The Android counterpart of iOS `AnnotationAnimator`: the scale-pop enter/exit animation of map
 * annotations (0.01 → 1 on enter, back on exit, 0.2s easeInOut). This file is animation
 * infrastructure only — what an annotation looks like stays in AnnotationOverlay.
 *
 * Android divergence in the mechanics: instead of animating each view (iOS lets UIKit drive each
 * `UIView.transform`), ONE frame ticker drives every marker. An Animatable per marker meant N
 * coroutine resumptions and N snapshot writes every frame, which alone stuttered the appear
 * animation when a dense load composed hundreds of markers at once. Entries own no coroutines;
 * each marker's scale is a pure function of the shared frame time.
 */
package com.chizberg.rewind.features.map.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.tracing.trace
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.features.map.AnnotationValue
import kotlinx.coroutines.flow.first

// iOS AnnotationAnimator: scale 0.01 <-> 1 over 0.2s. The helper animates with UIKit's default
// `options: []` curve, i.e. easeInOut — CubicBezier(0.42, 0, 0.58, 1) is its exact match.
private const val SUPER_SMALL = 0.01f
private const val ANIM_DURATION_MS = 200
private val EaseInOutCubic = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * One tracked annotation view: its stable [key], the latest model, and its enter/exit animation
 * state. Only [value] and [animating] are snapshot state (composition reads the former, draw
 * gates on the latter); the rest are plain fields touched solely on the main thread by the
 * reconciler and the ticker.
 */
internal class PresenceEntry(
    val key: String,
    value: AnnotationValue,
) {
    var value by mutableStateOf(value)
    var animating by mutableStateOf(true)

    /** Whether the entry is shrinking out; removed by the ticker once the shrink completes. */
    var exiting = false

    /** Scale the current animation run started from (mid-flight reversals resume seamlessly). */
    var startScale = SUPER_SMALL

    /** Scale the current run is heading to: 1 entering/settled, [SUPER_SMALL] exiting. */
    var target = 1f

    /** Frame time the current run started at; <0 = starts on the ticker's next frame. */
    var startMs = -1L

    fun scaleAt(nowMs: Long): Float {
        if (!animating) return target
        if (startMs < 0) return startScale
        val fraction = ((nowMs - startMs).toFloat() / ANIM_DURATION_MS).coerceIn(0f, 1f)
        return startScale + (target - startScale) * EaseInOutCubic.transform(fraction)
    }

    /** (Re)starts the animation towards [newTarget] from wherever the scale currently is. */
    fun animateTo(
        newTarget: Float,
        nowMs: Long,
    ) {
        startScale = scaleAt(nowMs)
        startMs = -1
        target = newTarget
        animating = true
    }
}

/**
 * The overlay's annotation bookkeeping: the composed [entries] (including ones still shrinking
 * out) plus the frame clock of the single shared ticker. One ticker coroutine and one
 * [frameTimeMs] write per frame drive every marker's animation.
 */
internal class AnnotationPresence {
    val entries = mutableStateListOf<PresenceEntry>()

    /** Last ticker frame time; every animating marker derives its scale from it in draw. */
    val frameTimeMs = mutableLongStateOf(0L)
}

/**
 * Reconciles [annotations] into a presence list that also holds annotations that have left the
 * source but are still animating out. New keys are appended (they enter from [SUPER_SMALL]);
 * vanished keys begin their shrink if still near the viewport — ones culled beyond [keep] are
 * dropped instantly, their exit would play off-screen and only cost frames; a key that reappears
 * mid-exit reverses and grows back.
 *
 * The single ticker below sleeps until something animates, then stamps [AnnotationPresence
 * .frameTimeMs], starts pending runs, settles finished ones, and removes completed exits — for
 * the whole overlay.
 */
@Composable
internal fun rememberAnnotationPresence(
    annotations: List<AnnotationValue>,
    keep: Region,
): AnnotationPresence {
    val presence = remember { AnnotationPresence() }
    LaunchedEffect(annotations) {
        trace("Rewind:reconcile") {
            val entries = presence.entries
            val now = presence.frameTimeMs.longValue
            val incoming = annotations.associateBy { it.key() }
            val existingByKey = entries.associateBy { it.key }
            incoming.forEach { (key, value) ->
                val existing = existingByKey[key]
                if (existing == null) {
                    entries.add(PresenceEntry(key, value))
                } else {
                    existing.value = value
                    if (existing.exiting) {
                        existing.exiting = false
                        existing.animateTo(1f, now)
                    }
                }
            }
            val dropped = mutableListOf<PresenceEntry>()
            entries.forEach { entry ->
                if (incoming.containsKey(entry.key)) return@forEach
                if (keep.contains(entry.value.coordinate())) {
                    if (!entry.exiting) {
                        entry.exiting = true
                        entry.animateTo(SUPER_SMALL, now)
                    }
                } else {
                    dropped += entry
                }
            }
            entries.removeAll(dropped)
        }
    }
    LaunchedEffect(presence) {
        while (true) {
            snapshotFlow { presence.entries.any { it.animating } }.first { it }
            while (presence.entries.any { it.animating }) {
                withFrameMillis { now ->
                    trace("Rewind:tick") {
                        presence.frameTimeMs.longValue = now
                        presence.entries.forEach { entry ->
                            if (!entry.animating) return@forEach
                            if (entry.startMs < 0) {
                                entry.startMs = now
                            } else if (now - entry.startMs >= ANIM_DURATION_MS) {
                                entry.animating = false
                            }
                        }
                        presence.entries.removeAll { it.exiting && !it.animating }
                    }
                }
            }
        }
    }
    return presence
}

/**
 * Applies [entry]'s enter/exit scale. Scale is applied after layout, so it never changes the
 * measured size used for centring; graphicsLayer's default origin is the (already-centred) view
 * centre. Settled markers read only `animating` — the ticking frame time is observed (and re-runs
 * the layer block) exclusively while the marker animates.
 */
internal fun Modifier.presenceScale(
    entry: PresenceEntry,
    frameTimeMs: LongState,
): Modifier =
    graphicsLayer {
        val scale = if (entry.animating) entry.scaleAt(frameTimeMs.longValue) else entry.target
        scaleX = scale
        scaleY = scale
    }
