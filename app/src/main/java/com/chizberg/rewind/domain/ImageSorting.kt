package com.chizberg.rewind.domain

/** Sort order for image lists. Port of iOS `ImageSorting`. */
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
