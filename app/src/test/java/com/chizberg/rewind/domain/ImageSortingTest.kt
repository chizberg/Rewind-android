package com.chizberg.rewind.domain

import com.chizberg.rewind.network.dto.NetworkImage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirror of iOS ImageSortingTests: only the deterministic branches (.DateAscending /
 * .DateDescending). `.Shuffle` is a nondeterministic stdlib pass-through — not tested.
 * Descending is asserted against literals, not the ascending output reversed (a symmetric-
 * comparator bug could pass that).
 */
class ImageSortingTest {
    private fun image(
        cid: Int,
        year: Int,
    ): ModelImage =
        ModelImage(
            NetworkImage(
                cid = cid,
                file = "$cid.jpg",
                title = "t$cid",
                dir = null,
                geo = listOf(0.0, 0.0),
                year = year,
                year2 = year,
            ),
        )

    @Test
    fun dateAscendingOrdersByDate() {
        val images =
            listOf(
                image(cid = 1, year = 1900),
                image(cid = 2, year = 1850),
                image(cid = 3, year = 2000),
            )
        val sorted = images.sorted(ImageSorting.DateAscending)
        assertEquals(listOf(1850, 1900, 2000), sorted.map { it.date.year })
        assertEquals(listOf(2, 1, 3), sorted.map { it.cid })
    }

    @Test
    fun dateDescendingOrdersByDateDesc() {
        val images =
            listOf(
                image(cid = 1, year = 1900),
                image(cid = 2, year = 1850),
                image(cid = 3, year = 2000),
            )
        val desc = images.sorted(ImageSorting.DateDescending)
        assertEquals(listOf(2000, 1900, 1850), desc.map { it.date.year })
        assertEquals(listOf(3, 1, 2), desc.map { it.cid })
    }
}
