package com.chizberg.rewind.domain

/**
 * Map query filters: which years and photos-vs-paintings. Port of iOS `ImageRequestFilters`.
 * Immutable (value semantics via `copy`), matching the redux immutable-state style.
 */
data class ImageRequestFilters(
    val yearRange: IntRange,
    val imageKind: ImageKind,
) {
    enum class ImageKind {
        Photo,
        Painting,
        ;

        val isPainting: Boolean get() = this == Painting
        val isPhoto: Boolean get() = this == Photo

        val maxRange: IntRange
            get() =
                when (this) {
                    Photo -> 1826..2000
                    Painting -> -100..1980
                }
    }

    constructor(imageKind: ImageKind) : this(yearRange = imageKind.maxRange, imageKind = imageKind)

    val isRangeModified: Boolean
        get() = yearRange != imageKind.maxRange

    companion object {
        val default = ImageRequestFilters(imageKind = ImageKind.Photo)
    }
}
