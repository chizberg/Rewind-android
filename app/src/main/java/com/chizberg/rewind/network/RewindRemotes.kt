package com.chizberg.rewind.network

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.domain.StreetViewAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's concrete remotes. Port of iOS `RewindRemotes`. */
data class RewindRemotes(
    val annotations: Remote<AnnotationLoadingParams, Pair<List<ModelImage>, List<ModelCluster>>>,
    val imageDetails: Remote<Int, ModelImageDetails>,
    val streetViewAvailability: Remote<Coordinate, StreetViewAvailability>,
    val translate: Remote<TranslateParams, String>,
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
 * Text + target language for one Cloud Translation call. Port of iOS `TranslateParams`, which sits
 * in this very file there. Re-exported into the details feature (see its `TranslateParams` alias):
 * the image-details reducer is the only caller, and its signature reads as iOS's does.
 */
data class TranslateParams(
    val text: String,
    val target: String,
)

/**
 * Builds the production remotes over [requestPerformer]. Port of iOS
 * `RewindRemotes.init(requestPerformer:imageLoader:)`. Divergence: no image loader — the DTO
 * `file` path is carried into [ModelImage]/[ModelCluster] for Coil to load later.
 */
operator fun RewindRemotes.Companion.invoke(requestPerformer: RequestPerformer): RewindRemotes {
    val annotations =
        Remote<AnnotationLoadingParams, Pair<List<ModelImage>, List<ModelCluster>>> { params ->
            // The whole load stays off the caller's thread: the reducer scope is Main.immediate,
            // and mapping a z17-sized batch (10k+ photos) to models is a visible main-thread stall.
            withContext(Dispatchers.Default) {
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
            }
        }.exponentialBackoff()
    val imageDetails =
        Remote<Int, ModelImageDetails> { cid ->
            ModelImageDetails(requestPerformer.perform(Request.imageDetails(cid)))
        }.exponentialBackoff()
    // No `exponentialBackoff()` on either Google remote, exactly as on iOS: the two PastVu remotes
    // above are wrapped, these two are not. A retried metadata lookup would only delay the
    // "unavailable" alert the comparison screen is waiting on, and a retried translation would
    // likewise sit on the "Unable to translate description" alert while the button stays spinning.
    val streetViewAvailability =
        Remote<Coordinate, StreetViewAvailability> { coordinate ->
            requestPerformer.perform(Request.streetViewAvailability(coordinate))
        }
    val translate =
        Remote<TranslateParams, String> { params ->
            requestPerformer.perform(Request.translate(params))
        }
    return RewindRemotes(
        annotations = annotations,
        imageDetails = imageDetails,
        streetViewAvailability = streetViewAvailability,
        translate = translate,
    )
}
