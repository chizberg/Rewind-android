package com.chizberg.rewind.colorization

import com.chizberg.rewind.core.util.Job
import com.chizberg.rewind.network.DownloadPerformer
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job as CoroutineJob

/** Port of iOS `ColorizationFileState`. */
sealed interface ColorizationFileState {
    data object Available : ColorizationFileState

    data class Downloading(
        val fraction: Float,
    ) : ColorizationFileState

    data object Installing : ColorizationFileState

    data object Downloaded : ColorizationFileState

    val isDownloading: Boolean get() = this is Downloading

    val isInProgress: Boolean get() = this is Downloading || this is Installing
}

/**
 * The models on disk and the downloads that put them there. Port of iOS `ColorizationModelStore`.
 * Like iOS's `@MainActor` class it is used from the main thread only; [scope] runs its downloads.
 *
 * [makeModel] wraps an installed graph into a [ColorizationModel] — the runtime is the app layer's
 * (ONNX Runtime), which keeps this class JVM-only.
 */
@Suppress("LongParameterList")
class ColorizationModelStore(
    private val downloadPerformer: DownloadPerformer,
    private val manifest: Remote<Unit, ColorizationManifest>,
    private val baseUrl: String,
    private val modelsDirectory: File,
    private val temporaryDirectory: File,
    private val makeModel: (ColorizationModelId, File) -> ColorizationModel,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableMapOf<ColorizationModelId, DownloadJob>()

    /**
     * The model handed out last, kept so the next colorize reuses the graph it already loaded; one
     * at a time, because DDColor's weights alone run to hundreds of megabytes.
     */
    private var loadedModel: Pair<ColorizationModelId, ColorizationModel>? = null

    init {
        // An unpack the process did not survive leaves a graph-sized file behind; no download is
        // running yet, so whatever is there is such a leftover.
        scope.launch(Dispatchers.IO) { temporaryDirectory.listFiles()?.forEach { it.delete() } }
    }

    fun fileState(id: ColorizationModelId): ColorizationFileState =
        when (val state = jobs[id]?.state?.value) {
            is Job.State.Running ->
                when (val progress = state.progress) {
                    is DownloadPerformer.Progress.Downloading ->
                        ColorizationFileState.Downloading(progress.fraction)
                    DownloadPerformer.Progress.ProcessingFile -> ColorizationFileState.Installing
                }
            is Job.State.Finished, null ->
                if (localModelFile(id) == null) {
                    ColorizationFileState.Available
                } else {
                    ColorizationFileState.Downloaded
                }
        }

    fun deleteFile(id: ColorizationModelId) {
        if (loadedModel?.first == id) release()
        val file = localModelFile(id) ?: return
        if (!file.delete()) throw IOException("Unable to delete ${file.name}")
    }

    fun clearCache() {
        release()
    }

    fun localModel(id: ColorizationModelId): ColorizationModel? {
        val file = localModelFile(id) ?: return null
        loadedModel?.let { (loadedId, model) -> if (loadedId == id) return model }
        release()
        return makeModel(id, file).also { loadedModel = id to it }
    }

    /** iOS lets ARC free the graph; a runtime's native session is closed by hand (after the run
     *  that may still hold it — that is the model's business). */
    private fun release() {
        (loadedModel?.second as? AutoCloseable)?.close()
        loadedModel = null
    }

    @Suppress("TooGenericExceptionCaught")
    fun download(id: ColorizationModelId): DownloadJob {
        jobs[id]?.let { return it }
        val state =
            MutableStateFlow<Job.State<DownloadPerformer.Progress, Unit>>(
                Job.State.Running(DownloadPerformer.Progress.Downloading(0f)),
            )
        val task =
            scope.launch {
                var transfer: Job<DownloadPerformer.Progress, File>? = null
                var mirroring: CoroutineJob? = null
                try {
                    val entry = manifest.load(Unit).entry(id)
                    val started =
                        downloadPerformer.perform(
                            colorizationModelDownload(entry, baseUrl, temporaryDirectory),
                        )
                    transfer = started
                    mirroring =
                        launch {
                            started.state.collect { if (it is Job.State.Running) state.value = it }
                        }
                    val unpacked = started.value()
                    mirroring.cancel()
                    place(unpacked, id)
                    state.value = Job.State.Finished(Result.success(Unit))
                } catch (e: CancellationException) {
                    transfer?.cancel()
                    state.value = Job.State.Finished(Result.failure(e))
                } catch (e: Exception) {
                    state.value = Job.State.Finished(Result.failure(e))
                } finally {
                    // The mirror collects a StateFlow, which never completes: on a failure it
                    // would keep this coroutine alive for good.
                    mirroring?.cancel()
                    jobs.remove(id)
                }
            }
        return Job(state, cancel = { task.cancel() }).also { jobs[id] = it }
    }

    private suspend fun place(
        unpacked: File,
        id: ColorizationModelId,
    ) = withContext(Dispatchers.IO) {
        modelsDirectory.mkdirs()
        val destination = modelFile(id)
        if (!unpacked.renameTo(destination)) {
            unpacked.copyTo(destination, overwrite = true)
            unpacked.delete()
        }
    }

    private fun localModelFile(id: ColorizationModelId): File? =
        modelFile(id).takeIf { it.exists() }

    private fun modelFile(id: ColorizationModelId) = File(modelsDirectory, "${id.rawValue}.onnx")
}

typealias DownloadJob = Job<DownloadPerformer.Progress, Unit>
