package com.chizberg.rewind.app

import kotlin.coroutines.cancellation.CancellationException

/**
 * A presentable alert. Port of iOS `AlertParams` (App/AlertPresenter.swift), trimmed to the data
 * the Compose alert host needs: a [title], a [message] and which stock buttons go with them. iOS
 * carries the button actions themselves; here the two shapes it actually builds — `.error` (Copy to
 * clipboard + OK) and `.info` (OK only) — collapse into [isError], so the alert host supplies the
 * buttons instead of every call site threading closures through the state.
 */
data class AlertParams(
    val title: String? = null,
    val message: String? = null,
    /** iOS `.error` attaches a "Copy to clipboard" action; `.info` (M12's "nothing found") does not. */
    val isError: Boolean = true,
    /** An extra, non-stock button. Never combined with the error's copy button (see [Action]). */
    val action: Action? = null,
) {
    /**
     * Port of one iOS `AlertParams.Action` beyond the stock "OK": a [handler] plus the [kind] that
     * names it. The label is a resource the alert host picks, not a string carried here — reducers
     * building alerts are JVM-only and hold no `R` references (the same reason alert bodies are
     * still English literals; localising them is its own task, see M12).
     *
     * At most one such action exists per alert, and the host renders it in the slot the error's
     * "Copy to clipboard" would take — no alert this app builds wants both.
     */
    data class Action(
        val kind: Kind,
        val handler: () -> Unit,
    ) {
        enum class Kind {
            /** M13.5's "Go to Settings" — the way out of a denied location permission. */
            OpenSettings,
        }
    }
}

/** iOS `AlertParams.error`: the error's description becomes the (copyable) message. */
fun errorAlert(
    title: String,
    error: Throwable,
): AlertParams = AlertParams(title = title, message = error.toString())

/** iOS `AlertParams.info`: a plain message with a single OK — nothing to copy. */
fun infoAlert(
    title: String,
    message: String,
): AlertParams = AlertParams(title = title, message = message, isError = false)

/**
 * iOS `AlertParams.nonCancelledError`: no alert for cancelled work (a superseded load is not a
 * user-facing failure). Returns null so the caller can present nothing.
 */
fun nonCancelledError(
    title: String,
    error: Throwable,
): AlertParams? = if (error is CancellationException) null else errorAlert(title, error)
