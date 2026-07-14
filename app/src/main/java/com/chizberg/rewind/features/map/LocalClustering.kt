package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelLocalCluster
import com.chizberg.rewind.domain.delta
import com.chizberg.rewind.network.AnnotationLoadingParams
import kotlin.math.floor

private const val LOCAL_CLUSTER_MIN_COUNT = 5
private const val CLUSTERING_CELL_RATIO = 8.0

/**
 * Folds a received `(images, clusters)` batch into [state]. Line-for-line port of iOS
 * `makeDiffAfterReceived`. On a zoom/filter change everything is rebuilt from scratch; a same-zoom
 * pan applies additive-only patches so already-shown annotations don't churn. The very first load
 * (`lastLoadedParams == null`) never clears.
 */
fun makeDiffAfterReceived(
    images: List<ModelImage>,
    clusters: List<ModelCluster>,
    params: AnnotationLoadingParams,
    state: MapState,
): ClusteringDiff {
    val last = state.lastLoadedParams
    val shouldClear = last != null && (last.zoom != params.zoom || last.filters != params.filters)

    val toAdd = mutableListOf<AnnotationValue>()
    val toRemove = mutableListOf<AnnotationValue>()

    // Clusters: replace all on a clear, otherwise add only the newly-received ones.
    val receivedClusters = clusters.toSet()
    val newClusters: Set<ModelCluster> =
        if (shouldClear) {
            toRemove += state.clusters.map { AnnotationValue.Cluster(it) }
            toAdd += receivedClusters.map { AnnotationValue.Cluster(it) }
            receivedClusters
        } else {
            val added = receivedClusters - state.clusters
            toAdd += added.map { AnnotationValue.Cluster(it) }
            state.clusters + added
        }

    // Clustered images: rebuild from scratch on a zoom/filter change, else incremental patches.
    val receivedImages = images.toSet()
    val newClusteredImages =
        if (shouldClear) {
            regroupFromScratch(receivedImages, params.zoom, state.clusteredImages, toAdd, toRemove)
        } else {
            applyIncremental(receivedImages, params.zoom, state.clusteredImages, toAdd, toRemove)
        }

    return ClusteringDiff(
        state = state.copy(clusters = newClusters, clusteredImages = newClusteredImages),
        toAdd = toAdd,
        toRemove = toRemove,
    )
}

private fun regroupFromScratch(
    receivedImages: Set<ModelImage>,
    zoom: Int,
    current: Map<ClusteringCell, CellValue>,
    toAdd: MutableList<AnnotationValue>,
    toRemove: MutableList<AnnotationValue>,
): Map<ClusteringCell, CellValue> {
    val freeImages = mutableSetOf<ModelImage>()
    for (cellValue in current.values) {
        when (cellValue) {
            is CellValue.Free -> freeImages += cellValue.images
            // Kept out of `freeImages` so they re-add as individuals if the cluster splits.
            is CellValue.Clustered -> toRemove += AnnotationValue.LocalCluster(cellValue.cluster)
        }
    }
    val staleImages = freeImages - receivedImages
    toRemove += staleImages.map { AnnotationValue.Image(it) }

    val regrouped = mutableMapOf<ClusteringCell, CellValue>()
    for ((cell, cellImages) in groupImages(receivedImages, zoom)) {
        if (cellImages.size < LOCAL_CLUSTER_MIN_COUNT) {
            regrouped[cell] = CellValue.Free(cellImages)
            toAdd += (cellImages - freeImages).map { AnnotationValue.Image(it) }
        } else {
            val cluster =
                ModelLocalCluster(images = cellImages.toList(), coordinate = cell.coordinate)
            regrouped[cell] = CellValue.Clustered(cluster)
            toAdd += AnnotationValue.LocalCluster(cluster)
            toRemove += cellImages.intersect(freeImages).map { AnnotationValue.Image(it) }
        }
    }
    return regrouped
}

private fun applyIncremental(
    receivedImages: Set<ModelImage>,
    zoom: Int,
    current: Map<ClusteringCell, CellValue>,
    toAdd: MutableList<AnnotationValue>,
    toRemove: MutableList<AnnotationValue>,
): Map<ClusteringCell, CellValue> {
    val patches =
        groupImages(receivedImages, zoom).mapNotNull { (cell, newImagesForCell) ->
            makePatch(cell, newImagesForCell, current[cell])?.let { cell to it }
        }

    val result = current.toMutableMap()
    for ((cell, patch) in patches) {
        when (patch) {
            is Patch.AddImages -> {
                val existing = result[cell]?.left ?: emptySet()
                result[cell] = CellValue.Free(existing + patch.images)
                toAdd += patch.images.map { AnnotationValue.Image(it) }
            }
            is Patch.AddCluster -> {
                result[cell] = CellValue.Clustered(patch.cluster)
                toAdd += AnnotationValue.LocalCluster(patch.cluster)
                toRemove += patch.removing.map { AnnotationValue.Image(it) }
            }
            is Patch.AddImagesToCluster -> {
                val cluster = result[cell]?.right ?: continue
                val merged =
                    ModelLocalCluster(
                        images = cluster.images + patch.images,
                        coordinate = cluster.coordinate,
                        // fresh id — a grown cluster is a new annotation identity
                    )
                result[cell] = CellValue.Clustered(merged)
                toAdd += AnnotationValue.LocalCluster(merged)
                toRemove += AnnotationValue.LocalCluster(cluster)
            }
        }
    }
    return result
}

private fun groupImages(
    images: Set<ModelImage>,
    zoom: Int,
): Map<ClusteringCell, Set<ModelImage>> {
    val size = delta(zoom) / CLUSTERING_CELL_RATIO
    val result = mutableMapOf<ClusteringCell, MutableSet<ModelImage>>()
    for (image in images) {
        val cell =
            ClusteringCell(
                latIndex = floor(image.coordinate.latitude / size).toInt(),
                lonIndex = floor(image.coordinate.longitude / size).toInt(),
                size = size,
            )
        result.getOrPut(cell) { mutableSetOf() }.add(image)
    }
    return result
}

private sealed interface Patch {
    data class AddImages(
        val images: Set<ModelImage>,
    ) : Patch

    data class AddCluster(
        val cluster: ModelLocalCluster,
        val removing: Set<ModelImage>,
    ) : Patch

    data class AddImagesToCluster(
        val images: Set<ModelImage>,
    ) : Patch
}

private fun makePatch(
    cell: ClusteringCell,
    newImages: Set<ModelImage>,
    current: CellValue?,
): Patch? {
    if (current == null) {
        return if (newImages.size < LOCAL_CLUSTER_MIN_COUNT) {
            Patch.AddImages(newImages)
        } else {
            Patch.AddCluster(
                ModelLocalCluster(newImages.toList(), cell.coordinate),
                removing = emptySet(),
            )
        }
    }
    return when (current) {
        is CellValue.Free -> {
            val existing = current.images
            val imagesToAdd = newImages - existing
            when {
                imagesToAdd.isEmpty() -> null
                existing.size + imagesToAdd.size < LOCAL_CLUSTER_MIN_COUNT ->
                    Patch.AddImages(imagesToAdd)
                else ->
                    Patch.AddCluster(
                        ModelLocalCluster((existing + imagesToAdd).toList(), cell.coordinate),
                        removing = existing,
                    )
            }
        }
        is CellValue.Clustered -> {
            val imagesToAdd = newImages - current.cluster.images.toSet()
            if (imagesToAdd.isEmpty()) null else Patch.AddImagesToCluster(imagesToAdd)
        }
    }
}
