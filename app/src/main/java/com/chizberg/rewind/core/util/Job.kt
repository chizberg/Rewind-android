package com.chizberg.rewind.core.util

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile

/**
 * Work that reports progress while it runs and ends in a result. Port of iOS `Job<Progress,
 * Success>`: a state to observe plus a way to stop it.
 */
class Job<Progress, Success>(
    val state: StateFlow<State<Progress, Success>>,
    val cancel: () -> Unit,
) {
    sealed interface State<out Progress, out Success> {
        data class Running<Progress>(
            val progress: Progress,
        ) : State<Progress, Nothing>

        data class Finished<Success>(
            val result: Result<Success>,
        ) : State<Nothing, Success>
    }

    /** iOS `value`: the result once the job finishes, its failure thrown. */
    suspend fun value(): Success {
        val finished = state.first { it is State.Finished } as State.Finished<Success>
        return finished.result.getOrThrow()
    }

    /** iOS `states`: every state up to and including the finished one. */
    suspend fun collectStates(onState: suspend (State<Progress, Success>) -> Unit) {
        state
            .transformWhile {
                emit(it)
                it !is State.Finished
            }.collect(onState)
    }
}
