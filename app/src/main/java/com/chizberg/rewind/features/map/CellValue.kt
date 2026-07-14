package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelLocalCluster

/**
 * What a [ClusteringCell] holds: either loose images (below the cluster threshold) or a merged
 * [ModelLocalCluster]. Port of iOS `Either<Set<Model.Image>, Model.LocalCluster>`; the [left]/
 * [right] accessors keep the same names as the iOS `Either` so both codebases read in parallel.
 */
sealed interface CellValue {
    data class Free(
        val images: Set<ModelImage>,
    ) : CellValue

    data class Clustered(
        val cluster: ModelLocalCluster,
    ) : CellValue

    val left: Set<ModelImage>? get() = (this as? Free)?.images
    val right: ModelLocalCluster? get() = (this as? Clustered)?.cluster
}
