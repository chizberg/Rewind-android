@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.core.redux

import app.cash.turbine.test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// MARK: - Test fixtures

private data class TestState(val count: Int = 0, val applied: List<String> = emptyList())

private typealias TestAsyncEffect = AsyncEffect<TestAction>

private sealed interface TestAction {
    data object Increment : TestAction
    data class Add(val value: Int) : TestAction
    data class Mark(val tag: String) : TestAction

    /** Enqueue synchronous effects (run right after reduce, on the calling stack). */
    data class SyncEffects(val effects: List<Effect>) : TestAction

    /** Enqueue asynchronous effects (run later, each in its own cancellable Job). */
    data class AsyncEffects(val effects: List<TestAsyncEffect>) : TestAction

    /** Enqueue both kinds in a single reduce, to test their relative ordering. */
    data class Mixed(val sync: List<Effect>, val async: List<TestAsyncEffect>) : TestAction
}

private fun makeReducer(
    scope: CoroutineScope,
    initial: TestState = TestState(),
): Reducer<TestState, TestAction> = Reducer(initial, scope) { state, action, effect, asyncEffect ->
    when (action) {
        TestAction.Increment -> state.copy(count = state.count + 1)
        is TestAction.Add -> state.copy(count = state.count + action.value)
        is TestAction.Mark -> state.copy(applied = state.applied + action.tag)
        is TestAction.SyncEffects -> {
            action.effects.forEach(effect)
            state
        }
        is TestAction.AsyncEffects -> {
            action.effects.forEach(asyncEffect)
            state
        }
        is TestAction.Mixed -> {
            action.sync.forEach(effect)
            action.async.forEach(asyncEffect)
            state
        }
    }
}

/**
 * Runs a test giving the reducer a scope backed by the test scheduler (NOT `backgroundScope`, whose
 * tasks `advanceUntilIdle()` deliberately ignores). The scope is cancelled afterwards so long-lived
 * collectors (`onStateUpdate`/`adding`) don't leak into `runTest`'s completion check.
 */
private fun reducerTest(body: suspend TestScope.(scope: CoroutineScope) -> Unit) = runTest {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    try {
        body(scope)
    } finally {
        scope.cancel()
    }
}

// MARK: - Synchronous state mutation

class ReducerSyncTest {
    @Test
    fun singleActionMutatesState() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(TestAction.Increment)
        assertEquals(1, reducer.state.value.count)
    }

    @Test
    fun multipleActionsAccumulate() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(TestAction.Increment)
        reducer(TestAction.Increment)
        reducer(TestAction.Increment)
        reducer(TestAction.Add(2))
        assertEquals(5, reducer.state.value.count)
    }

    @Test
    fun mutationIsSynchronous() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(TestAction.Mark("a"))
        reducer(TestAction.Mark("b"))
        assertEquals(listOf("a", "b"), reducer.state.value.applied)
    }
}

// MARK: - Synchronous effects

class ReducerSyncEffectTest {
    @Test
    fun syncEffectRunsImmediatelyAfterReduce() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        var ran = false
        // Synchronous effects run on the calling stack — no coroutine, no await.
        reducer(TestAction.SyncEffects(listOf({ ran = true })))
        assertEquals(true, ran)
    }

    @Test
    fun syncEffectsRunInEnqueueOrder() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        val log = mutableListOf<Int>()
        reducer(
            TestAction.SyncEffects(
                listOf(
                    { log += 1 },
                    { log += 2 },
                    { log += 3 },
                ),
            ),
        )
        assertEquals(listOf(1, 2, 3), log)
    }

    @Test
    fun syncEffectMaySafelyDispatchFollowUpAction() = reducerTest { scope ->
        // Sync effects run after the reducer clears its `isRunning` guard, so re-entering the same
        // reducer from a sync effect is allowed (synchronous, no coroutine hop) and does not trip
        // the recursion check.
        val reducer = makeReducer(scope)
        reducer(TestAction.SyncEffects(listOf({ reducer(TestAction.Add(5)) })))
        assertEquals(5, reducer.state.value.count)
    }

    @Test
    fun syncEffectsRunBeforeAsyncEffectsAreScheduled() = reducerTest { scope ->
        // Headline invariant of the dual-effect model: within ONE reduce call, every synchronous
        // effect runs (on the calling stack) before any async effect is scheduled.
        val reducer = makeReducer(scope)
        reducer(
            TestAction.Mixed(
                sync = listOf({ reducer(TestAction.Mark("sync")) }),
                async = listOf(AsyncEffect.anotherAction(action = TestAction.Mark("async"))),
            ),
        )
        // Sync effect has already run; the async effect is only scheduled, not run.
        assertEquals(listOf("sync"), reducer.state.value.applied)
        advanceUntilIdle()
        assertEquals(listOf("sync", "async"), reducer.state.value.applied)
    }
}

// MARK: - Asynchronous effects

class ReducerAsyncEffectTest {
    @Test
    fun multipleEffectsPerActionAllRun() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.anotherAction(id = "a", action = TestAction.Increment),
                    AsyncEffect.anotherAction(id = "b", action = TestAction.Increment),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(2, reducer.state.value.count)
    }

    @Test
    fun performEffectDispatchesFollowUp() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.perform(id = "p") { send -> send(TestAction.Add(7)) },
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(7, reducer.state.value.count)
    }

    @Test
    fun anotherActionEffectDispatchesFollowUp() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(AsyncEffect.anotherAction(id = "x", action = TestAction.Increment)),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, reducer.state.value.count)
    }

    @Test
    fun afterEffectFiresAfterDelay() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.after(
                        50.milliseconds,
                        id = "t",
                        anotherAction = TestAction.Increment,
                    ),
                ),
            ),
        )
        assertEquals(0, reducer.state.value.count) // scheduled, not run yet
        advanceUntilIdle()
        assertEquals(1, reducer.state.value.count)
    }
}

// MARK: - Cancellation / deduplication by effect id

class ReducerCancellationTest {
    @Test
    fun sameIdReplacesPendingEffect() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        // First effect would add 10 after a long delay...
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.after(
                        300.milliseconds,
                        id = "x",
                        anotherAction = TestAction.Add(10),
                    ),
                ),
            ),
        )
        // ...but a second effect with the same id supersedes it.
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.after(50.milliseconds, id = "x", anotherAction = TestAction.Add(1)),
                ),
            ),
        )

        advanceUntilIdle() // exhausts virtual time: the cancelled add(10) can never fire
        assertEquals(1, reducer.state.value.count)
    }

    @Test
    fun cancelEffectStopsPendingFollowUp() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.after(
                        200.milliseconds,
                        id = "x",
                        anotherAction = TestAction.Add(10),
                    ),
                ),
            ),
        )
        reducer(TestAction.AsyncEffects(listOf(AsyncEffect.cancel(id = "x"))))

        advanceUntilIdle()
        assertEquals(0, reducer.state.value.count)
    }

    @Test
    fun differentIdsRunIndependently() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.after(
                        50.milliseconds,
                        id = "a",
                        anotherAction = TestAction.Increment,
                    ),
                    AsyncEffect.after(
                        50.milliseconds,
                        id = "b",
                        anotherAction = TestAction.Increment,
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(2, reducer.state.value.count)
    }

    @Test
    fun effectIdCanBeReusedAfterCancel() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.after(
                        200.milliseconds,
                        id = "x",
                        anotherAction = TestAction.Add(10),
                    ),
                ),
            ),
        )
        reducer(TestAction.AsyncEffects(listOf(AsyncEffect.cancel(id = "x"))))
        // Re-arm the same id with a fresh effect; it should fire exactly once.
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.after(
                        50.milliseconds,
                        id = "x",
                        anotherAction = TestAction.Increment,
                    ),
                ),
            ),
        )

        advanceUntilIdle()
        assertEquals(1, reducer.state.value.count) // the cancelled add(10) never fires
    }
}

// MARK: - Debounce

class ReducerDebounceTest {
    @Test
    fun debouncedCollapsesRapidDispatches() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        // Five rapid debounced dispatches share one id → only the last survives.
        repeat(5) {
            reducer(
                TestAction.AsyncEffects(
                    listOf(
                        AsyncEffect.debounced(
                            DebouncedActionId.RegionChanged,
                            anotherAction = TestAction.Increment,
                        ),
                    ),
                ),
            )
        }
        advanceUntilIdle()
        assertEquals(1, reducer.state.value.count)
    }

    @Test
    fun debouncedFiresExactlyAtDelayBoundary() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.debounced(
                        DebouncedActionId.RegionChanged,
                        anotherAction = TestAction.Increment,
                    ),
                ),
            ),
        )

        advanceTimeBy(99.milliseconds)
        runCurrent()
        assertEquals(0, reducer.state.value.count) // 1ms short of the 100ms debounce

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1, reducer.state.value.count) // fires exactly at 100ms
    }

    @Test
    fun debouncedClosureFormRunsAfterDelay() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.debounced(DebouncedActionId.RegionChanged) { send ->
                        send(TestAction.Add(3))
                    },
                ),
            ),
        )
        assertEquals(0, reducer.state.value.count) // debounce delay not elapsed yet
        advanceUntilIdle()
        assertEquals(3, reducer.state.value.count)
    }

    @Test
    fun cancelDebouncedStopsPendingFollowUp() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer(
            TestAction.AsyncEffects(
                listOf(
                    AsyncEffect.debounced(
                        DebouncedActionId.RegionChanged,
                        anotherAction = TestAction.Add(10),
                    ),
                ),
            ),
        )
        reducer(
            TestAction.AsyncEffects(listOf(AsyncEffect.cancel(DebouncedActionId.RegionChanged))),
        )

        advanceUntilIdle()
        assertEquals(0, reducer.state.value.count)
    }

    @Test
    fun debouncedActionIdDelays() {
        assertEquals(100.milliseconds, DebouncedActionId.RegionChanged.delay)
        assertEquals(100.milliseconds, DebouncedActionId.UpdatePreviews.delay)
        assertEquals(100.milliseconds, DebouncedActionId.FiltersChanged.delay)
        assertEquals(2.seconds, DebouncedActionId.UnfoldControlsBack.delay)
    }
}

// MARK: - Recursion guard (Android addition; iOS uses a stripped-in-release `assert`)

class ReducerRecursionGuardTest {
    @Test
    fun recursiveDispatchDuringReduceThrows() = reducerTest { scope ->
        lateinit var reducer: Reducer<TestState, TestAction>
        reducer = Reducer(TestState(), scope) { state, _, _, _ ->
            reducer(TestAction.Increment) // dispatch DURING reduce → guard must trip
            state
        }
        assertThrows(IllegalStateException::class.java) { reducer(TestAction.Increment) }
    }
}

// MARK: - Reducer extensions

class ReducerExtensionTest {
    @Test
    fun onStateUpdateReceivesCurrentAndNewValues() = reducerTest { scope ->
        // Divergence from iOS: VGSL observes synchronously; a StateFlow collector runs on `scope`.
        // Advancing the scheduler after each dispatch prevents conflation so every value is observed.
        val observed = mutableListOf<Int>()
        val reducer = makeReducer(scope).onStateUpdate { observed += it.count }
        advanceUntilIdle() // collector subscribes, receives initial 0
        reducer(TestAction.Increment)
        advanceUntilIdle()
        reducer(TestAction.Increment)
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), observed)
    }

    @Test
    fun addingFlowDispatchesAction() = reducerTest { scope ->
        val pipe = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val reducer = makeReducer(scope).adding(pipe) { TestAction.Add(it) }
        advanceUntilIdle() // start collecting
        pipe.tryEmit(5)
        advanceUntilIdle()
        assertEquals(5, reducer.state.value.count)
    }
}

// MARK: - StateFlow emissions (Turbine)

class ReducerStateFlowTest {
    @Test
    fun stateFlowEmitsUpdates() = reducerTest { scope ->
        val reducer = makeReducer(scope)
        reducer.state.test {
            assertEquals(0, awaitItem().count) // initial
            reducer(TestAction.Increment)
            assertEquals(1, awaitItem().count)
            reducer(TestAction.Add(4))
            assertEquals(5, awaitItem().count)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// MARK: - ViewStore

class ViewStoreTest {
    @Test
    fun readsStateViaCurrent() = reducerTest { scope ->
        val store = makeReducer(scope, TestState(count = 42)).viewStore
        assertEquals(42, store.current.count)
    }

    @Test
    fun dispatchesActionsAndReflectsState() = reducerTest { scope ->
        val store = makeReducer(scope).viewStore
        store(TestAction.Increment)
        store(TestAction.Add(4))
        assertEquals(5, store.current.count)
    }

    @Test
    fun bimapMapsStateAndAction() = reducerTest { scope ->
        val store = makeReducer(scope).viewStore
        val mapped = store.bimap(
            state = { it.count },
            action = { value: Int -> TestAction.Add(value) },
        )
        mapped(6)
        assertEquals(6, mapped.current)
    }
}
