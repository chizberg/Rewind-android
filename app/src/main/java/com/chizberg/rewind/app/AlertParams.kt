package com.chizberg.rewind.app

import kotlin.coroutines.cancellation.CancellationException

/**
 * A presentable alert. Port of iOS `AlertParams` (App/AlertPresenter.swift), trimmed to the data
 * the Compose alert host needs: a [title] and a [message]. iOS carries the button actions too
 * (e.g. "Copy to clipboard" / "OK"); those are stock for our error alerts, so the alert host
 * supplies them rather than every call site threading closures through the state.
 */
data class AlertParams(
    val title: String? = null,
    val message: String? = null,
)

/** iOS `AlertParams.error`: the error's description becomes the (copyable) message. */
fun errorAlert(
    title: String,
    error: Throwable,
): AlertParams = AlertParams(title = title, message = error.toString())

/**
 * iOS `AlertParams.nonCancelledError`: no alert for cancelled work (a superseded load is not a
 * user-facing failure). Returns null so the caller can present nothing.
 */
fun nonCancelledError(
    title: String,
    error: Throwable,
): AlertParams? = if (error is CancellationException) null else errorAlert(title, error)
