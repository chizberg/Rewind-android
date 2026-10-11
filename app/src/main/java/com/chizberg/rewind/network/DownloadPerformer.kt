package com.chizberg.rewind.network

import com.chizberg.rewind.core.util.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Request as OkHttpRequest

/**
 * A file to download and what to make of it once it is on disk. Port of iOS
 * `Network.DownloadRequest<Response>`; [processFile] reads the downloaded file, which is deleted
 * after it returns.
 */
class DownloadRequest<Response>(
    val makeRequest: () -> OkHttpRequest,
    val processFile: suspend (File) -> Response,
)

/**
 * Downloads a [DownloadRequest] into a temporary file, reporting the fraction received, then hands
 * the file to the request. Port of iOS `DownloadPerformer`; OkHttp streams the body to disk here,
 * where `URLSession.downloadTask` did it there.
 */
class DownloadPerformer(
    private val client: OkHttpClient,
    private val temporaryDirectory: File,
    private val scope: CoroutineScope,
) {
    /** Whether the leftovers of an earlier process are gone; guarded by itself. */
    private var leftoversCleared = false
    private val leftoversLock = Any()

    sealed interface Progress {
        data class Downloading(
            val fraction: Float,
        ) : Progress

        data object ProcessingFile : Progress
    }

    @Suppress("TooGenericExceptionCaught")
    fun <Response> perform(request: DownloadRequest<Response>): Job<Progress, Response> {
        val state =
            MutableStateFlow<Job.State<Progress, Response>>(
                Job.State.Running(Progress.Downloading(0f)),
            )
        val task =
            scope.launch {
                state.value =
                    try {
                        Job.State.Finished(
                            Result.success(
                                process(request) { state.value = Job.State.Running(it) },
                            ),
                        )
                    } catch (e: CancellationException) {
                        Job.State.Finished(Result.failure(e))
                    } catch (e: Exception) {
                        Job.State.Finished(Result.failure(e))
                    }
            }
        return Job(state, cancel = { task.cancel() })
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <Response> process(
        request: DownloadRequest<Response>,
        onProgress: (Progress) -> Unit,
    ): Response {
        val file = fetch(request.makeRequest()) { onProgress(Progress.Downloading(it)) }
        try {
            onProgress(Progress.ProcessingFile)
            return request.processFile(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NetworkError.ParsingFailure(e)
        } finally {
            file.delete()
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun fetch(
        request: OkHttpRequest,
        onProgress: (Float) -> Unit,
    ): File {
        val response = call(request)
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw NetworkError.InvalidCode(it.code)
                val body = it.body ?: throw NetworkError.ParsingFailure(desc = "No file")
                val total = body.contentLength()
                clearLeftovers()
                temporaryDirectory.mkdirs()
                val destination = File(temporaryDirectory, UUID.randomUUID().toString())
                try {
                    copy(body.byteStream(), destination, total, onProgress)
                } catch (e: IOException) {
                    destination.delete()
                    throw NetworkError.ConnectionFailure(e)
                } catch (e: CancellationException) {
                    destination.delete()
                    throw e
                }
                destination
            }
        }
    }

    /**
     * A download the process did not survive leaves its partial file behind — hundreds of
     * megabytes for a colorization model. Before the first file of this process no download of
     * ours is running, so whatever is there is such a leftover; the lock keeps a second first
     * download from creating its file while the first one is still clearing.
     */
    private fun clearLeftovers() =
        synchronized(leftoversLock) {
            if (leftoversCleared) return
            temporaryDirectory.listFiles()?.forEach { it.delete() }
            leftoversCleared = true
        }

    private suspend fun copy(
        input: InputStream,
        destination: File,
        total: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var received = 0L
        var reported = -1
        input.use { stream ->
            destination.outputStream().use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    received += read
                    if (total > 0) {
                        // Whole percents only: the reducer gets an action per report.
                        val percent = (received * PERCENT / total).toInt()
                        if (percent != reported) {
                            reported = percent
                            onProgress(received.toFloat() / total)
                        }
                    }
                }
            }
        }
    }

    /** The response once its headers are in; cancelling the coroutine cancels the call. */
    private suspend fun call(request: OkHttpRequest): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(NetworkError.ConnectionFailure(e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                },
            )
        }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PERCENT = 100
    }
}
