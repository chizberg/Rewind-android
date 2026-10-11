package com.chizberg.rewind.features.colorization

import com.chizberg.rewind.app.AlertParams
import com.chizberg.rewind.app.errorAlert
import com.chizberg.rewind.colorization.ColorizationFileState
import com.chizberg.rewind.colorization.ColorizationManifest
import com.chizberg.rewind.colorization.ColorizationModelId
import com.chizberg.rewind.colorization.ColorizationModelStore
import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.core.util.Job
import com.chizberg.rewind.network.DownloadPerformer
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException

typealias ColorizationPickerModel = Reducer<ColorizationPickerState, ColorizationPickerAction>

/**
 * The model picker. Port of iOS `ColorizationPickerScreenState`, with its `Common` part and the
 * per-model file states flattened into one state — iOS merges one reducer per model into a
 * `ViewStore`; here one reducer keys the same transitions by model.
 */
data class ColorizationPickerState(
    val fileStates: Map<ColorizationModelId, ColorizationFileState>,
    val picked: ColorizationModelId?,
    /** Archive sizes from the manifest, in bytes; the view formats them. Null until it loads. */
    val sizes: Map<ColorizationModelId, Long>? = null,
    val alert: AlertParams? = null,
    val modelIds: List<ColorizationModelId> = ColorizationModelId.entries,
    /** iOS `colorize != nil`: opened from a photo, the screen offers to colorize it once a model
     *  is picked; opened from settings, it only manages the models. */
    val canColorize: Boolean,
)

sealed interface ColorizationPickerAction {
    data class Pick(
        val id: ColorizationModelId?,
    ) : ColorizationPickerAction

    data object LoadManifest : ColorizationPickerAction

    data class ManifestLoaded(
        val manifest: ColorizationManifest,
    ) : ColorizationPickerAction

    data class ManifestFailed(
        val error: Throwable,
    ) : ColorizationPickerAction

    data object DismissAlert : ColorizationPickerAction

    /** iOS calls the `colorize` closure from the button directly; here the tap is an action. */
    data object Colorize : ColorizationPickerAction

    data class File(
        val id: ColorizationModelId,
        val action: ColorizationFileAction,
    ) : ColorizationPickerAction
}

/** Port of iOS `ColorizationFileAction`, `.ui` and `.internal` flattened. */
sealed interface ColorizationFileAction {
    data object Download : ColorizationFileAction

    data object CancelDownload : ColorizationFileAction

    data object Delete : ColorizationFileAction

    data object ObserveDownload : ColorizationFileAction

    data class DownloadProgressChanged(
        val fraction: Float,
    ) : ColorizationFileAction

    data object InstallationStarted : ColorizationFileAction

    data object DownloadFinished : ColorizationFileAction

    data class Failed(
        val error: Throwable,
    ) : ColorizationFileAction
}

/**
 * Builds the picker. Port of iOS `makeColorizationPickerScreenStore` + `makeColorizationFileModel`.
 *
 * [pickedModel] is the settings field; [colorize] is null when the screen is opened from settings.
 * A download keeps running when the screen closes — the store owns it, as on iOS — and a screen
 * opened meanwhile picks the running job up again.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
fun makeColorizationPickerModel(
    modelStore: ColorizationModelStore,
    pickedModel: Property<ColorizationModelId?>,
    manifest: Remote<Unit, ColorizationManifest>,
    colorize: (() -> Unit)?,
    scope: CoroutineScope,
): ColorizationPickerModel {
    val model =
        Reducer<ColorizationPickerState, ColorizationPickerAction>(
            initial =
                ColorizationPickerState(
                    fileStates = ColorizationModelId.entries.associateWith(modelStore::fileState),
                    picked = pickedModel.value,
                    canColorize = colorize != null,
                ),
            scope = scope,
        ) { state, action, effect, asyncEffect ->
            when (action) {
                is ColorizationPickerAction.Pick -> {
                    effect { pickedModel.value = action.id }
                    state.copy(picked = action.id)
                }

                ColorizationPickerAction.LoadManifest -> {
                    asyncEffect(
                        AsyncEffect.perform { send ->
                            try {
                                send(ColorizationPickerAction.ManifestLoaded(manifest.load(Unit)))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                send(ColorizationPickerAction.ManifestFailed(e))
                            }
                        },
                    )
                    state
                }

                is ColorizationPickerAction.ManifestLoaded ->
                    state.copy(
                        sizes =
                            ColorizationModelId.entries
                                .mapNotNull { id ->
                                    runCatching { action.manifest.entry(id) }
                                        .getOrNull()
                                        ?.let { id to it.bytes }
                                }.toMap(),
                    )

                is ColorizationPickerAction.ManifestFailed ->
                    state.copy(alert = errorAlert("Unable to load the model list", action.error))

                ColorizationPickerAction.DismissAlert -> state.copy(alert = null)

                ColorizationPickerAction.Colorize -> {
                    colorize?.let { effect(it) }
                    state
                }

                is ColorizationPickerAction.File ->
                    reduceFile(
                        state = state,
                        id = action.id,
                        action = action.action,
                        store = modelStore,
                        pickedModel = pickedModel,
                        effect = effect,
                        asyncEffect = asyncEffect,
                    )
            }
        }
    model(ColorizationPickerAction.LoadManifest)
    for (id in ColorizationModelId.entries) {
        if (model.state.value.fileStates
                .getValue(id)
                .isInProgress
        ) {
            model(ColorizationPickerAction.File(id, ColorizationFileAction.ObserveDownload))
        }
    }
    return model
}

/** Port of iOS `makeColorizationFileModel`'s reduce, for the model [id]. */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
private fun reduceFile(
    state: ColorizationPickerState,
    id: ColorizationModelId,
    action: ColorizationFileAction,
    store: ColorizationModelStore,
    pickedModel: Property<ColorizationModelId?>,
    effect: (() -> Unit) -> Unit,
    asyncEffect: (AsyncEffect<ColorizationPickerAction>) -> Unit,
): ColorizationPickerState {
    val fileState = state.fileStates.getValue(id)

    fun with(newState: ColorizationFileState) =
        state.copy(fileStates = state.fileStates + (id to newState))

    fun send(fileAction: ColorizationFileAction) = ColorizationPickerAction.File(id, fileAction)

    fun later(fileAction: ColorizationFileAction) =
        asyncEffect(AsyncEffect.anotherAction(action = send(fileAction)))
    val effectId = "download-${id.rawValue}"
    return when (action) {
        ColorizationFileAction.Download -> {
            if (fileState != ColorizationFileState.Available) return state
            later(ColorizationFileAction.ObserveDownload)
            with(ColorizationFileState.Downloading(0f))
        }

        ColorizationFileAction.CancelDownload -> {
            if (!fileState.isDownloading) return state
            // Ending the observation is what stops the download: like iOS's `states` stream, the
            // observer cancels the job when it goes away.
            asyncEffect(AsyncEffect.cancel(effectId))
            with(ColorizationFileState.Available)
        }

        ColorizationFileAction.Delete -> {
            if (fileState != ColorizationFileState.Downloaded) return state
            try {
                // iOS deletes from the reducer body too: the row has to know at once whether the
                // file went.
                store.deleteFile(id)
                if (state.picked == id) {
                    effect { pickedModel.value = null }
                    with(ColorizationFileState.Available).copy(picked = null)
                } else {
                    with(ColorizationFileState.Available)
                }
            } catch (e: Exception) {
                later(ColorizationFileAction.Failed(e))
                state
            }
        }

        ColorizationFileAction.ObserveDownload -> {
            if (!fileState.isInProgress) return state
            asyncEffect(
                AsyncEffect.perform(effectId) { dispatch ->
                    val job = store.download(id)
                    try {
                        job.collectStates { jobState ->
                            dispatch(send(jobState.toFileAction()))
                        }
                    } catch (e: CancellationException) {
                        job.cancel()
                        throw e
                    }
                },
            )
            state
        }

        is ColorizationFileAction.DownloadProgressChanged -> {
            if (!fileState.isDownloading) return state
            with(ColorizationFileState.Downloading(action.fraction))
        }

        ColorizationFileAction.InstallationStarted ->
            if (fileState.isDownloading) with(ColorizationFileState.Installing) else state

        ColorizationFileAction.DownloadFinished -> {
            val downloaded = with(ColorizationFileState.Downloaded)
            if (state.picked == null) {
                effect { pickedModel.value = id }
                downloaded.copy(picked = id)
            } else {
                downloaded
            }
        }

        // iOS leaves a TODO here for an alert; the row simply falls back to what is on disk.
        is ColorizationFileAction.Failed -> with(store.fileState(id))
    }
}

private fun Job.State<DownloadPerformer.Progress, Unit>.toFileAction(): ColorizationFileAction =
    when (this) {
        is Job.State.Running ->
            when (val progress = progress) {
                is DownloadPerformer.Progress.Downloading ->
                    ColorizationFileAction.DownloadProgressChanged(progress.fraction)
                DownloadPerformer.Progress.ProcessingFile ->
                    ColorizationFileAction.InstallationStarted
            }
        is Job.State.Finished ->
            result.fold(
                onSuccess = { ColorizationFileAction.DownloadFinished },
                onFailure = { ColorizationFileAction.Failed(it) },
            )
    }
