package com.chizberg.rewind.domain

import kotlinx.serialization.Serializable

/** Sort order for image lists. Port of iOS `ImageSorting` (a `Codable` enum); `@Serializable` so it
 *  persists by name inside the settings blob. */
@Serializable
enum class ImageSorting {
    DateAscending,
    DateDescending,
    Shuffle,
}

/**
 * Sorts images by the given [sorting]. Port of iOS `[Model.Image].sorted(by:)`
 * (the label `by` is a Kotlin hard keyword, so it becomes [sorting]).
 */
fun List<ModelImage>.sorted(sorting: ImageSorting): List<ModelImage> =
    when (sorting) {
        ImageSorting.DateAscending -> sortedBy { it.date }
        ImageSorting.DateDescending -> sortedByDescending { it.date }
        ImageSorting.Shuffle -> shuffled()
    }
