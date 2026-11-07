package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.MapType
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.network.AnnotationLoadingParams

/**
 * Whether the region sheet is minimized and by whom. Port of iOS `MinimizationState`.
 *
 * [Minimized.byUser] keeps the two drivers from fighting: a drag of the *map* near the bottom edge
 * minimizes the sheet and re-arms a 2s unfold, but once the user has minimized it *themselves* the
 * automatic driver stops touching it.
 */
sealed interface Minimization {
    data object Normal : Minimization

    data class Minimized(
        val byUser: Boolean = false,
    ) : Minimization

    val isMinimizedByUser: Boolean get() = this is Minimized && byUser
}

/**
 * The map screen's state. Port of iOS `MapState`.
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
    val cameraZoom: Float,
    val filters: ImageRequestFilters,
    val isLoading: Boolean,
    val lastLoadedParams: AnnotationLoadingParams?,
    val clusters: Set<ModelCluster>,
    val clusteredImages: Map<ClusteringCell, CellValue>,
    // The base map's style (iOS `MapState.mapType`). Transient, never persisted: it starts at
    // `Scheme` on every launch, exactly as iOS's `makeInitial` does.
    val mapType: MapType = MapType.Scheme,
    val currentRegionImages: List<ModelImage> = emptyList(),
    // The local-clustering grid cell's longitude span, held fixed for the whole rounded-zoom bucket
    // (LocalClustering recomputes it only when the bucket changes). 0.0 until the first load.
    val clusteringCellSpan: Double = 0.0,
    // The floating controls' own state (iOS `MapState.controls`).
    val controls: ControlsState = ControlsState(),
    // The device's location-tracking state (iOS `MapState.locationState`), mirrored in from the
    // location reducer via `NewLocationState`. iOS seeds it through `makeInitial(locationState:)`;
    // here the stream's first (replayed) value does the same job — see AppGraph.
    val locationState: LocationState = LocationState(),
    val deferredRegion: MapAction.Internal.RegionChanged? = null,
    val emptyResultCount: Int = 0,
    val sheetDetent: SheetDetent = SheetDetent.Normal,
    val bottomInset: Float = SHEET_NORMAL_HEIGHT,
    val toolbarKind: ToolbarKind = ToolbarKind.Map,
) {
    data class ControlsState(
        val isTimePickerVisible: Boolean = false,
        val minimization: Minimization = Minimization.Normal,
    )

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
                cameraZoom = INITIAL_ZOOM.toFloat(),
                filters = ImageRequestFilters.default,
                isLoading = false,
                lastLoadedParams = null,
                clusters = emptySet(),
                clusteredImages = emptyMap(),
            )
    }
}
