package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.network.AnnotationLoadingParams

/**
 * Actions for [makeMapModel]. Port of iOS `MapAction`, trimmed to M6 (region loading + filters);
 * annotation selection, location, map type and controls arrive with their later milestones. The
 * External.Map / External.Ui / Internal nesting mirrors iOS so both codebases read in parallel.
 */
sealed interface MapAction {
    sealed interface External : MapAction {
        sealed interface Map : External {
            /**
             * Camera settled on a new region. [zoom] is the rounded camera zoom (see Zoom.kt);
             * [cameraZoom] is the raw continuous zoom, kept so the clustering cell stays constant
             * within a rounded-zoom bucket (a sub-bucket zoom must not shift the grid).
             */
            data class RegionChanged(
                val region: Region,
                val zoom: Int,
                val cameraZoom: Float,
            ) : Map
        }

        sealed interface Ui : External {
            data class FiltersChanged(
                val filters: ImageRequestFilters,
            ) : Ui
        }
    }

    sealed interface Internal : MapAction {
        data class RegionChanged(
            val region: Region,
            val zoom: Int,
            val cameraZoom: Float,
        ) : Internal

        data object LoadAnnotations : Internal

        data class LoadingFailed(
            val error: Throwable,
        ) : Internal

        data class Loaded(
            val params: AnnotationLoadingParams,
            val images: List<ModelImage>,
            val clusters: List<ModelCluster>,
        ) : Internal

        data object UpdatePreviews : Internal

        data object ClearAnnotations : Internal
    }
}
