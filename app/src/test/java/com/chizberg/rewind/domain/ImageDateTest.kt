package com.chizberg.rewind.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirror of iOS ImageDateTests: description (single year vs range) and lexicographic order. */
class ImageDateTest {
    @Test
    fun comparableOrdersByYearFirst() {
        assertTrue(ImageDate(1890, 1895) < ImageDate(1900, 1800))
    }

    @Test
    fun comparableBreaksTieOnYear2() {
        // Same year -> must fall back to year2.
        assertTrue(ImageDate(1890, 1895) < ImageDate(1890, 1896))
        assertFalse(ImageDate(1890, 1896) < ImageDate(1890, 1895))
    }
}
