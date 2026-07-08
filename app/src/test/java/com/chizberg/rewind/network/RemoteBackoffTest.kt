@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.network

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Mirror of iOS RemoteBackoffTests, plus the exact-timing assertion the iOS suite can't make
 * without a virtual clock. Pins: the retry loop returns the first success; the 1s/2s delay
 * schedule (magic constants buried in the loop); cancelling the load aborts a pending retry.
 */
class RemoteBackoffTest {
    @Test
    fun retriesTwiceThenSucceeds() =
        runTest {
            var calls = 0
            val remote =
                Remote<Unit, Int> {
                    calls += 1
                    if (calls < 3) throw IOException("attempt $calls failed")
                    42
                }.exponentialBackoff()

            val result = remote.load(Unit)

            assertEquals(42, result)
            assertEquals(3, calls)
        }

    @Test
    fun backoffWaitsOneThenTwoSeconds() =
        runTest {
            var calls = 0
            val remote =
                Remote<Unit, Int> {
                    calls += 1
                    throw IOException("always fails")
                }.exponentialBackoff()
            val job = launch { runCatching { remote.load(Unit) } }

            runCurrent()
            assertEquals(1, calls) // first attempt fires immediately
            advanceTimeBy(999.milliseconds)
            runCurrent()
            assertEquals(1, calls) // still waiting out the 1s delay
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(2, calls) // second attempt at exactly 1s
            advanceTimeBy(1999.milliseconds)
            runCurrent()
            assertEquals(2, calls) // waiting out the 2s delay
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(3, calls) // third (final) attempt at 1s + 2s
            job.join()
        }

    @Test
    fun cancellationDuringBackoffAbortsRetries() =
        runTest {
            var calls = 0
            val remote =
                Remote<Unit, Int> {
                    calls += 1
                    throw IOException("always fails")
                }.exponentialBackoff()
            val job = launch { runCatching { remote.load(Unit) } }

            runCurrent()
            assertEquals(1, calls) // first attempt done; now suspended in the 1s backoff delay
            job.cancel()
            advanceUntilIdle()
            assertEquals(1, calls) // the cancelled delay aborts the retry — no second attempt
        }
}
