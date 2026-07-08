package com.chizberg.rewind.network

import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails

/** The app's concrete remotes. Port of iOS `RewindRemotes`. */
data class RewindRemotes(
    val annotations: Remote<AnnotationLoadingParams, Pair<List<ModelImage>, List<ModelCluster>>>,
    val imageDetails: Remote<Int, ModelImageDetails>,
) {
    companion object
}

/** Args for the map annotations request. Port of iOS `AnnotationLoadingParams`. */
data class AnnotationLoadingParams(
    val zoom: Int,
    val coordinates: List<List<Double>>,
    val startAt: Double,
    val filters: ImageRequestFilters,
)

/**
 * Builds the production remotes over [requestPerformer]. Port of iOS
 * `RewindRemotes.init(requestPerformer:imageLoader:)`. Divergence: no image loader — the DTO
 * `file` path is carried into [ModelImage]/[ModelCluster] for Coil to load later.
 */
operator fun RewindRemotes.Companion.invoke(requestPerformer: RequestPerformer): RewindRemotes {
    val annotations =
        Remote<AnnotationLoadingParams, Pair<List<ModelImage>, List<ModelCluster>>> { params ->
            val (networkImages, networkClusters) =
                requestPerformer.perform(
                    Request.byBounds(
                        zoom = params.zoom,
                        coordinates = params.coordinates,
                        startAt = params.startAt,
                        yearRange = params.filters.yearRange,
                        isPainting = params.filters.imageKind.isPainting,
                    ),
                )
            networkImages.map { ModelImage(it) } to networkClusters.map { ModelCluster(it) }
        }.exponentialBackoff()
    val imageDetails =
        Remote<Int, ModelImageDetails> { cid ->
            ModelImageDetails(requestPerformer.perform(Request.imageDetails(cid)))
        }.exponentialBackoff()
    return RewindRemotes(annotations = annotations, imageDetails = imageDetails)
}
