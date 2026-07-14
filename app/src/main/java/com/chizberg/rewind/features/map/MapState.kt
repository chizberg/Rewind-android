package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.network.AnnotationLoadingParams

/**
 * The map screen's state. Port of iOS `MapState`, trimmed to what M6 needs (clustering + loading);
 * previews, controls, map type and location state arrive with their later milestones.
 *
 * Divergence from iOS: carries [zoom] explicitly. iOS reconstructs zoom from the region span via
 * the map size; we read Google Maps' camera zoom directly (see Zoom.kt), so `regionChanged` brings
 * it in and [loadAnnotations] uses it to build the request.
 *
 * JVM-only: no com.google.* types — [clusteredImages] is keyed by the pure [ClusteringCell], and
 * the render layer projects [clusters]/[clusteredImages] onto Google Maps markers at the UI edge.
 */
data class MapState(
    val region: Region,
    val zoom: Int,
    val filters: ImageRequestFilters,
    val isLoading: Boolean,
    val lastLoadedParams: AnnotationLoadingParams?,
    val clusters: Set<ModelCluster>,
    val clusteredImages: Map<ClusteringCell, CellValue>,
) {
    /**
     * The full set of renderable annotations projected from [clusters] + [clusteredImages]. This is
     * the declarative-render source of truth: the map draws exactly these (replacing iOS's
     * imperative add/remove). A free cell contributes one marker per loose image; a clustered cell
     * contributes a single local-cluster marker.
     */
    val annotations: List<AnnotationValue>
        get() =
            buildList {
                clusters.forEach { add(AnnotationValue.Cluster(it)) }
                clusteredImages.values.forEach { cell ->
                    when (cell) {
                        is CellValue.Free -> cell.images.forEach { add(AnnotationValue.Image(it)) }
                        is CellValue.Clustered -> add(AnnotationValue.LocalCluster(cell.cluster))
                    }
                }
            }

    companion object {
        // region .zero mirrors iOS makeInitial; zoom is a placeholder overwritten by the first
        // regionChanged from the camera before any load fires.
        private const val INITIAL_ZOOM = 3

        val initial =
            MapState(
                region =
                    Region(
                        center = Coordinate.zero,
                        span = Span(latitudeDelta = 0.0, longitudeDelta = 0.0),
                    ),
                zoom = INITIAL_ZOOM,
                filters = ImageRequestFilters.default,
                isLoading = false,
                lastLoadedParams = null,
                clusters = emptySet(),
                clusteredImages = emptyMap(),
            )
    }
}
