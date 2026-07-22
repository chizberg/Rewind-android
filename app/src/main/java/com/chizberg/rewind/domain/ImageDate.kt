package com.chizberg.rewind.domain

import kotlinx.serialization.Serializable

/**
 * Time span of an image: `[year, year2]`. Port of iOS `ImageDate`.
 * Comparable lexicographically by (year, year2).
 *
 * `@Serializable` for the M10 persistence DTO `StorageImage` (kotlinx.serialization only, no
 * android.* — the JVM-only rule for domain types still holds).
 */
@Serializable
data class ImageDate(
    val year: Int,
    val year2: Int,
) : Comparable<ImageDate> {
    val description: String
        get() = if (year == year2) year.toString() else "$year - $year2"

    override fun compareTo(other: ImageDate): Int =
        compareValuesBy(this, other, {
            it.year
        }, { it.year2 })
}
