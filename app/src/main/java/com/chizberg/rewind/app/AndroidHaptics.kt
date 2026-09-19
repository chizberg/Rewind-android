package com.chizberg.rewind.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.chizberg.rewind.core.util.Haptics

/**
 * Plays [Haptics] through the window's own view. Android half of the facade canonised in
 * `knowledge/material3.md`: `.success` → `CONFIRM`, `.error` → `REJECT`, impact `.light` →
 * `VIRTUAL_KEY`, `selectionChanged` → `SEGMENT_TICK` where it exists (API 34; the tick M3 sliders
 * and pickers use) and `CLOCK_TICK` below it. `CONFIRM`/`REJECT` need no gate — they landed in API
 * 30 and this app starts at 31.
 *
 * `View.performHapticFeedback` rather than a `Vibrator`, which was the other option: the view route
 * needs no `VIBRATE` permission, obeys the system's "touch feedback" switch, and plays the
 * platform's own tuned patterns instead of a hand-rolled waveform — all three are what the iOS
 * generators do. Its cost is that it needs a `View`, which a reducer has not got, hence the
 * attach/detach below.
 *
 * Divergence from the letter of `material3.md` ("reads `LocalView.performHapticFeedback`"): three of
 * the five iOS call sites are inside reducers built by [AppGraph], outside composition entirely
 * (settings' scheme change, the favorite toggle, the "no app can open this route" branch), so a
 * composition-scoped facade could not reach them. Instead the graph owns one long-lived instance
 * and [HapticsHost] lends it the current view for as long as the composition lives — the same shape
 * as [OrientationLockHost] borrowing the Activity. The reference is dropped on dispose, so the
 * graph (which outlives activity recreation) never holds a stale view.
 */
class AndroidHaptics : Haptics {
    private var view: View? = null

    fun attach(view: View) {
        this.view = view
    }

    fun detach(view: View) {
        if (this.view === view) this.view = null
    }

    override fun success() = perform(HapticFeedbackConstants.CONFIRM)

    override fun error() = perform(HapticFeedbackConstants.REJECT)

    override fun impactLight() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    override fun selection() =
        perform(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.SEGMENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            },
        )

    private fun perform(feedbackConstant: Int) {
        view?.performHapticFeedback(feedbackConstant)
    }
}

/** Lends [haptics] the composition's view for as long as it is on screen (see [AndroidHaptics]). */
@Composable
fun HapticsHost(haptics: AndroidHaptics) {
    val view = LocalView.current
    DisposableEffect(view) {
        haptics.attach(view)
        onDispose { haptics.detach(view) }
    }
}
