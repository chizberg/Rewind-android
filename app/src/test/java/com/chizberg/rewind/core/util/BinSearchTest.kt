package com.chizberg.rewind.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirror of iOS BinSearchTests: `binSearchFirstEqualOrGreater` — a branchy binary search with a
 * non-standard loop invariant and three tail checks. Expected indices are hand-worked from the
 * contract ("index of the first element >= goal, or null"), never by re-running the search. These
 * boundary cases (empty, single, below/above/at/between) exercise branches the gradient pipeline
 * can't reach — it always clamps its parameter into `[first, last]`, so "goal above all -> null"
 * and the empty/single paths are only observable here.
 */
class BinSearchTest {
    @Test
    fun emptyListIsNull() {
        assertNull(emptyList<Double>().binSearchFirstEqualOrGreater(5.0))
    }

    @Test
    fun singleElement() {
        assertEquals(0, listOf(5.0).binSearchFirstEqualOrGreater(5.0)) // equal
        assertEquals(0, listOf(5.0).binSearchFirstEqualOrGreater(4.0)) // greater
        assertNull(listOf(5.0).binSearchFirstEqualOrGreater(6.0)) // none >= goal
    }

    // arr = [1, 3, 5, 7, 9]; answers are the first index whose value is >= goal.
    @Test
    fun exactMatchReturnsThatIndex() {
        assertEquals(2, listOf(1.0, 3.0, 5.0, 7.0, 9.0).binSearchFirstEqualOrGreater(5.0))
    }

    @Test
    fun betweenElementsReturnsUpperNeighbour() {
        // 4 sits between 3 and 5 -> first element >= 4 is 5 at index 2.
        assertEquals(2, listOf(1.0, 3.0, 5.0, 7.0, 9.0).binSearchFirstEqualOrGreater(4.0))
    }

    @Test
    fun belowAllReturnsFirst() {
        assertEquals(0, listOf(1.0, 3.0, 5.0, 7.0, 9.0).binSearchFirstEqualOrGreater(0.0))
    }

    @Test
    fun atLastReturnsLast() {
        assertEquals(4, listOf(1.0, 3.0, 5.0, 7.0, 9.0).binSearchFirstEqualOrGreater(9.0))
    }

    @Test
    fun aboveAllIsNull() {
        assertNull(listOf(1.0, 3.0, 5.0, 7.0, 9.0).binSearchFirstEqualOrGreater(10.0))
    }
}
