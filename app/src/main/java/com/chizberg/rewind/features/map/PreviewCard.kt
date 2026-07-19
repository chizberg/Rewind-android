package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ModelImage

/**
 * A card in the map's bottom preview strip. Port of iOS `ThumbnailCard`: either an image, the
 * trailing "view as list" card (shown when there are more images than fit), or the "no images"
 * placeholder (shown when the visible region is empty). [id] gives Compose a stable key for the
 * strip's item animations (mirrors the iOS `Identifiable` id).
 */
sealed interface PreviewCard {
    data object NoImages : PreviewCard

    data class Image(
        val value: ModelImage,
    ) : PreviewCard

    data object ViewAsList : PreviewCard

    val id: String
        get() =
            when (this) {
                NoImages -> "noImages"
                is Image -> value.cid.toString()
                ViewAsList -> "viewAsList"
            }

    val image: ModelImage?
        get() = (this as? Image)?.value
}
