package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.sorted

/**
 * The images the region sheet draws from: every image visible in
 * [region], deduped and sorted. Port of iOS `MapModel.updateRegionImages`.
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

/** The image(s) an annotation contributes to the region list. Mirrors iOS `updateRegionImages`' flatten. */
private fun AnnotationValue.previewImages(): List<ModelImage> =
    when (this) {
        is AnnotationValue.Image -> listOf(value)
        is AnnotationValue.Cluster -> listOf(value.preview)
        is AnnotationValue.LocalCluster -> value.images
    }
