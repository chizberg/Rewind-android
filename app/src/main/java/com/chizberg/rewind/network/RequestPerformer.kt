package com.chizberg.rewind.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Request as OkHttpRequest

/** Raw transport: run an HTTP request, return its body bytes and status code. */
typealias UrlRequestPerformer = suspend (OkHttpRequest) -> Pair<ByteArray, Int>

/**
 * Runs typed [Request]s over an injectable transport. Port of iOS `RequestPerformer`.
 * The transport is a lambda so tests can feed canned bytes; production wires OkHttp via
 * [okHttpRequestPerformer]. `CancellationException` is always rethrown so coroutine
 * cancellation is never masked as a network error.
 */
class RequestPerformer(
    private val urlRequestPerformer: UrlRequestPerformer,
) {
    // Broad catches are intentional: any parse/transport failure is wrapped into NetworkError,
    // mirroring iOS `catch { throw NetworkError.parsingFailure(error) }`. Cancellation is rethrown.
    // Runs on Default: callers live on Main.immediate (the reducer scope), and byBounds bodies are
    // multi-megabyte — decoding + parsing them on the main thread would eat whole frames.
    @Suppress("TooGenericExceptionCaught")
    suspend fun <Response> perform(request: Request<Response>): Response =
        withContext(Dispatchers.Default) {
            val bytes = fetch(request.makeRequest())
            try {
                request.parseResult(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw NetworkError.ParsingFailure(e)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetch(request: OkHttpRequest): ByteArray =
        try {
            val (bytes, code) = urlRequestPerformer(request)
            if (code !in HTTP_OK_RANGE) throw NetworkError.InvalidCode(code)
            bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NetworkError.ConnectionFailure(e)
        }

    private companion object {
        val HTTP_OK_RANGE = 200..299
    }
}

/** Production transport backed by OkHttp; cancelling the coroutine cancels the call (socket). */
fun okHttpRequestPerformer(client: OkHttpClient): UrlRequestPerformer =
    { request ->
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            continuation.resume((it.body?.bytes() ?: ByteArray(0)) to it.code)
                        }
                    }
                },
            )
        }
    }
