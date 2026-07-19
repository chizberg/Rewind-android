package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.sorted

/** How many image cards the strip shows before collapsing the tail into a "view as list" card. */
const val PREVIEW_LIMIT = 10

/**
 * The images the preview strip and the "current region" list draw from: every image visible in
 * [region], deduped and sorted. Port of the first half of iOS `MapModel.updatePreviews`.
 *
 * Divergence from iOS: iOS asks MapKit for `visibleAnnotations` (the on-screen rect); we filter
 * [annotations] by [Region.contains] instead (no round-trip to the map view — simpler, and state
 * is already the render source of truth). Each annotation contributes its underlying image(s):
 * a loose image → itself, a server cluster → its preview, a local cluster → all its images.
 * Dedup is by cid (ModelImage equals/hashCode are cid-only), so a cluster preview that repeats a
 * loose image collapses into one entry.
 */
fun regionImages(
    annotations: List<AnnotationValue>,
    region: Region,
    sorting: ImageSorting,
): List<ModelImage> =
    annotations
        .filter { region.contains(it.coordinate()) }
        .flatMap { it.previewImages() }
        .toSet()
        .toList()
        .sorted(sorting)

/**
 * Builds the strip cards from the (already sorted) region [images]. Port of iOS `makePreviews`:
 * empty → a single "no images" card; more than [limit] → the first [limit] plus a "view as list"
 * tail card; otherwise one card per image.
 */
fun makePreviews(
    images: List<ModelImage>,
    limit: Int = PREVIEW_LIMIT,
): List<PreviewCard> =
    when {
        images.isEmpty() -> listOf(PreviewCard.NoImages)
        images.size > limit ->
            images.take(limit).map { PreviewCard.Image(it) } + PreviewCard.ViewAsList
        else -> images.map { PreviewCard.Image(it) }
    }

/** The image(s) an annotation contributes to the region list. Mirrors iOS `updatePreviews`'s flatten. */
private fun AnnotationValue.previewImages(): List<ModelImage> =
    when (this) {
        is AnnotationValue.Image -> listOf(value)
        is AnnotationValue.Cluster -> listOf(value.preview)
        is AnnotationValue.LocalCluster -> value.images
    }
