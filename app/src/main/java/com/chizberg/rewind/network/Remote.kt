package com.chizberg.rewind.network

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A loadable resource: `Args` in, `Response` out. Port of iOS `Remote<Args, Response>`.
 * Composable via [mapArgs] / [mapResponse] / [exponentialBackoff].
 */
class Remote<Args, Response>(
    val impl: suspend (Args) -> Response,
) {
    suspend fun load(args: Args): Response = impl(args)

    fun <NewArgs> mapArgs(transform: (NewArgs) -> Args): Remote<NewArgs, Response> =
        Remote { args -> impl(transform(args)) }

    fun <NewResponse> mapResponse(
        transform: (Response) -> NewResponse,
    ): Remote<Args, NewResponse> = Remote { args -> transform(impl(args)) }

    /**
     * Retries a failing load up to [attemptCount] times, waiting an exponentially growing delay
     * between attempts (1s then 2s by default). Any failure is retryable (broad catch mirrors
     * iOS `catch { }`) except [CancellationException], which propagates immediately so a cancelled
     * load aborts its pending retry. On iOS that falls out of `Task.sleep` throwing on
     * cancellation; here the delay lives outside the retry `catch`.
     */
    @Suppress("TooGenericExceptionCaught")
    fun exponentialBackoff(
        attemptCount: Int = 3,
        initialDelay: Duration = 1.seconds,
        factor: Double = 2.0,
    ): Remote<Args, Response> =
        Remote { args ->
            var currentDelay = initialDelay
            var lastError: Throwable? = null
            for (attempt in 0 until attemptCount) {
                try {
                    return@Remote impl(args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < attemptCount - 1) {
                        delay(currentDelay)
                        currentDelay *= factor
                    }
                }
            }
            throw lastError ?: error("backoff exhausted with no error")
        }
}
