package com.chizberg.rewind.core.redux

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A synchronous side effect, run on the calling stack right after `reduce`. Mirrors iOS `Reducer.Effect`. */
typealias Effect = () -> Unit

/**
 * Pure reduce function: maps `(state, action)` to a new state while enqueueing effects.
 *
 * iOS mutates `inout State`; here we return a fresh `State` (data-class `copy`). The returned value
 * is written to the backing [MutableStateFlow] synchronously, before any effect runs.
 */
typealias Reduce<State, Action> = (
    state: State,
    action: Action,
    effect: (Effect) -> Unit,
    asyncEffect: (AsyncEffect<Action>) -> Unit,
) -> State

/**
 * TCA-like state container, ported 1:1 from iOS `Utils/Reducer.swift`.
 *
 * Main-thread confined (the iOS `@MainActor` equivalent): [scope] MUST use a single-threaded main
 * dispatcher — `Dispatchers.Main.immediate` in production, a `StandardTestDispatcher` in tests. All
 * mutation and effect bookkeeping runs on that one thread, so no synchronization is required.
 */
class Reducer<State, Action>(
    initial: State,
    private val scope: CoroutineScope,
    private val reduce: Reduce<State, Action>,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<State> get() = _state

    /** In-flight async effects keyed by id. A new effect with the same id cancels and replaces the old one. */
    private val asyncEffects = HashMap<String, Job>()
    private var isRunning = false

    operator fun invoke(action: Action) {
        // Guards ONLY the reduce phase: re-entering during reduce would corrupt state. Dispatching
        // from a (post-reduce) sync effect is allowed and must NOT trip this. Mirrors iOS `assert`.
        check(!isRunning) {
            "Calling the same reducer recursively leads to unexpected state changes"
        }

        val newEffects = ArrayList<Effect>()
        val newAsyncEffects = ArrayList<AsyncEffect<Action>>()

        isRunning = true
        _state.value = reduce(
            _state.value,
            action,
            { newEffects.add(it) },
            { newAsyncEffects.add(it) },
        )
        isRunning = false

        // Sync effects: in enqueue order, on the calling stack (may safely re-dispatch).
        for (e in newEffects) e()

        // Async effects: each in its own cancellable Job, deduplicated by id.
        for (ae in newAsyncEffects) {
            asyncEffects.remove(ae.id)?.cancel()
            val job = scope.launch { ae.action { followUp -> this@Reducer(followUp) } }
            asyncEffects[ae.id] = job
            // Remove our own entry on completion — but only if it hasn't been replaced meanwhile.
            // (Job identity plays the role of iOS's per-task UUID token.)
            job.invokeOnCompletion {
                if (asyncEffects[ae.id] === job) asyncEffects.remove(ae.id)
            }
        }
    }

    /** iOS `adding(signal:)`: feed an external [flow] into the reducer as actions. Returns `this` for chaining. */
    fun <Value> adding(flow: Flow<Value>, makeAction: (Value) -> Action): Reducer<State, Action> {
        scope.launch { flow.collect { this@Reducer(makeAction(it)) } }
        return this
    }

    /**
     * iOS `onStateUpdate`: observe the current value plus every subsequent one. Returns `this`.
     *
     * Divergence (documented): iOS VGSL fires synchronously on the calling stack and never drops
     * intermediate values; a [StateFlow] collector runs on [scope] and conflates rapid changes.
     * State correctness is identical — only observation granularity differs.
     */
    fun onStateUpdate(perform: (State) -> Unit): Reducer<State, Action> {
        scope.launch { _state.collect { perform(it) } }
        return this
    }
}

/** Shared debounce ids and their delays. Same values as iOS `DebouncedActionID`. */
enum class DebouncedActionId(val delay: Duration) {
    RegionChanged(100.milliseconds),
    UpdatePreviews(100.milliseconds),
    FiltersChanged(100.milliseconds),
    UnfoldControlsBack(2.seconds),
    ;

    /** Key used to deduplicate / cancel the underlying async effect. */
    val id: String get() = name
}

/**
 * A cancellable asynchronous effect. Mirrors iOS `Reducer.AsyncEffect`.
 *
 * [action] receives a `send` function to dispatch follow-up actions back into the reducer.
 */
class AsyncEffect<Action>(
    val id: String,
    val action: suspend (send: suspend (Action) -> Unit) -> Unit,
) {
    companion object {
        private fun freshId(): String = UUID.randomUUID().toString()

        /** Run arbitrary async work, dispatching follow-ups through `send`. */
        fun <Action> perform(
            id: String = freshId(),
            action: suspend (send: suspend (Action) -> Unit) -> Unit,
        ): AsyncEffect<Action> = AsyncEffect(id, action)

        /** Dispatch a single [action] asynchronously. */
        fun <Action> anotherAction(id: String = freshId(), action: Action): AsyncEffect<Action> =
            AsyncEffect(id) { send -> send(action) }

        /**
         * Dispatch [anotherAction] after [delay]. If the effect is cancelled first, `delay` throws
         * `CancellationException` and nothing is dispatched — equivalent to iOS swallowing the error.
         */
        fun <Action> after(
            delay: Duration,
            id: String = freshId(),
            anotherAction: Action,
        ): AsyncEffect<Action> = AsyncEffect(id) { send ->
            delay(delay)
            send(anotherAction)
        }

        /** An empty effect that just cancels any in-flight effect with [id]. */
        fun <Action> cancel(id: String): AsyncEffect<Action> = AsyncEffect(id) { }

        /** Cancel the in-flight debounced effect for [debouncedAction]. */
        fun <Action> cancel(debouncedAction: DebouncedActionId): AsyncEffect<Action> =
            cancel<Action>(debouncedAction.id)

        /** Debounced arbitrary work: waits [id]`.delay`, then runs [action]. */
        fun <Action> debounced(
            id: DebouncedActionId,
            action: suspend (send: suspend (Action) -> Unit) -> Unit,
        ): AsyncEffect<Action> = AsyncEffect(id.id) { send ->
            delay(id.delay)
            action(send)
        }

        /** Debounced single action: waits [id]`.delay`, then dispatches [anotherAction]. */
        fun <Action> debounced(id: DebouncedActionId, anotherAction: Action): AsyncEffect<Action> =
            AsyncEffect(id.id) { send ->
                delay(id.delay)
                send(anotherAction)
            }
    }
}
