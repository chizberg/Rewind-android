package com.chizberg.rewind.features.map

import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.DebouncedActionId
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.network.AnnotationLoadingParams
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException

/** Fixed async-effect id: a fresh load cancels the in-flight one for free. Same id as iOS. */
private const val LOAD_ANNOTATIONS_ID = "load_annotations"
private const val MILLIS_PER_SECOND = 1000.0

/** The map screen's annotations remote. */
typealias AnnotationsRemote =
    Remote<AnnotationLoadingParams, Pair<List<ModelImage>, List<ModelCluster>>>

/**
 * Builds the map reducer. Port of iOS `makeMapModel`, scoped to M6. Divergences:
 * - **declarative render:** `.loaded` folds the clustering diff into state (`clusters`/
 *   `clusteredImages`) instead of imperative map add/remove; the render layer draws from state.
 * - **no map-view dependency:** the region and its [MapState.zoom] arrive via `regionChanged`, so
 *   the reducer is a pure function of state + injected [annotationsRemote] (cleanly unit-testable).
 * - **cancellation:** the load effect rethrows [CancellationException] instead of dispatching
 *   `loadingFailed`, so a cancelled load is silent (iOS dispatches `loadingFailed` and suppresses
 *   the alert via a nil `nonCancelledError`; same observable result).
 *
 * [onLoadFailed] stands in for the M9 `performAppAction(.alert(...))`; [now] supplies the request's
 * `startAt` cursor, injected so tests stay deterministic.
 */
@Suppress("TooGenericExceptionCaught")
fun makeMapModel(
    annotationsRemote: AnnotationsRemote,
    onLoadFailed: (Throwable) -> Unit,
    scope: CoroutineScope,
    now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
): Reducer<MapState, MapAction> =
    Reducer(
        initial = MapState.initial,
        scope = scope,
    ) { state, action, effect, asyncEffect ->
        when (action) {
            is MapAction.External.Map.RegionChanged -> {
                val internal =
                    MapAction.Internal.RegionChanged(action.region, action.zoom, action.cameraZoom)
                asyncEffect(
                    AsyncEffect.debounced(
                        id = DebouncedActionId.RegionChanged,
                        anotherAction = internal,
                    ),
                )
                state
            }

            is MapAction.External.Ui.FiltersChanged -> {
                // Switching photos <-> paintings resets the year range to the kind's full range.
                val imageKindChanged = state.filters.imageKind != action.filters.imageKind
                val newFilters =
                    if (imageKindChanged) {
                        action.filters.copy(yearRange = action.filters.imageKind.maxRange)
                    } else {
                        action.filters
                    }
                asyncEffect(
                    AsyncEffect.debounced(DebouncedActionId.FiltersChanged) { send ->
                        send(MapAction.Internal.ClearAnnotations)
                        send(MapAction.Internal.LoadAnnotations)
                    },
                )
                state.copy(filters = newFilters)
            }

            is MapAction.Internal.RegionChanged -> {
                asyncEffect(AsyncEffect.anotherAction(action = MapAction.Internal.LoadAnnotations))
                asyncEffect(
                    AsyncEffect.debounced(
                        id = DebouncedActionId.UpdatePreviews,
                        anotherAction = MapAction.Internal.UpdatePreviews,
                    ),
                )
                state.copy(
                    region = action.region,
                    zoom = action.zoom,
                    cameraZoom = action.cameraZoom,
                )
            }

            MapAction.Internal.LoadAnnotations -> {
                val params =
                    AnnotationLoadingParams(
                        zoom = state.zoom,
                        coordinates = state.region.geoJsonCoordinates,
                        startAt = now(),
                        filters = state.filters,
                    )
                asyncEffect(
                    AsyncEffect.perform(id = LOAD_ANNOTATIONS_ID) { send ->
                        try {
                            val (images, clusters) = annotationsRemote.load(params)
                            send(MapAction.Internal.Loaded(params, images, clusters))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            send(MapAction.Internal.LoadingFailed(e))
                        }
                    },
                )
                state.copy(isLoading = true)
            }

            is MapAction.Internal.Loaded -> {
                val diff =
                    makeDiffAfterReceived(action.images, action.clusters, action.params, state)
                asyncEffect(AsyncEffect.anotherAction(action = MapAction.Internal.UpdatePreviews))
                diff.state.copy(isLoading = false, lastLoadedParams = action.params)
            }

            is MapAction.Internal.LoadingFailed -> {
                effect { onLoadFailed(action.error) }
                state.copy(isLoading = false)
            }

            // Previews land in M8; the debounce wiring is here, the computation is still a stub.
            MapAction.Internal.UpdatePreviews -> state

            MapAction.Internal.ClearAnnotations -> {
                // Debounced with the same id as regionChanged's updatePreviews, so a clear followed
                // quickly by a region change coalesces into one preview pass (mirrors iOS).
                asyncEffect(
                    AsyncEffect.debounced(
                        id = DebouncedActionId.UpdatePreviews,
                        anotherAction = MapAction.Internal.UpdatePreviews,
                    ),
                )
                state.copy(clusters = emptySet(), clusteredImages = emptyMap())
            }
        }
    }
