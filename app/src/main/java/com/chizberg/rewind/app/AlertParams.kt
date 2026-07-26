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
)

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
