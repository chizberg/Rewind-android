package com.chizberg.rewind.features.map

import com.chizberg.rewind.app.AlertParams
import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.DebouncedActionId
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.network.AnnotationLoadingParams
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException

/** Fixed async-effect id: a fresh load cancels the in-flight one for free. Same id as iOS. */
private const val LOAD_ANNOTATIONS_ID = "load_annotations"
private const val MILLIS_PER_SECOND = 1000.0

/** Zoom the location button settles at, unless the camera is already closer in (iOS `locationZoom`). */
private const val LOCATION_ZOOM = 17

/** Zoom the one-off recenter on the first fix lands at (iOS `newLocationState`'s `zoom: 15`). */
private const val FIRST_FIX_ZOOM = 15f

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
 * `startAt` cursor, injected so tests stay deterministic. [sorting] is read fresh on each preview
 * pass (iOS reads `sorting.value`, a `Variable` fed from settings); until settings land it defaults
 * to iOS's `SettingsState.default.sorting`.
 *
 * M13.5 adds the location trio, all injected as lambdas (iOS holds the `LocationModel` itself, plus
 * `performAppAction`/`urlOpener`): [locationModel] receives the two manager commands, [presentAlert]
 * raises the two location alerts, [openAppSettings] is the handler behind "Go to Settings" (iOS
 * `urlOpener(UIApplication.openSettingsURLString)`), and [focusCamera] carries every recenter.
 */
@Suppress("TooGenericExceptionCaught")
fun makeMapModel(
    annotationsRemote: AnnotationsRemote,
    onLoadFailed: (Throwable) -> Unit,
    scope: CoroutineScope,
    now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    sorting: () -> ImageSorting = { ImageSorting.DateAscending },
    // Emits a camera recenter request — the Android stand-in for iOS's direct
    // `map.value.set(region:animated:)` call inside `locationButtonTapped`/`newLocationState`,
    // since the camera lives in Compose, not the reducer (same channel as `AppGraph.focusRequests`).
    focusCamera: (CameraFocus) -> Unit = {},
    locationModel: (LocationAction) -> Unit = {},
    presentAlert: (AlertParams) -> Unit = {},
    openAppSettings: () -> Unit = {},
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

            // iOS also fires `applyMapType(mapType)` here, an imperative poke at the live
            // `MKMapView`; ours is declarative — `RewindMap` reads the field off the state — so the
            // state change is the whole branch.
            is MapAction.External.Ui.MapTypeSelected -> state.copy(mapType = action.mapType)

            is MapAction.External.Ui.Controls.SetExpandedItems ->
                state.copy(controls = state.controls.copy(expandedItems = action.items))

            MapAction.External.Ui.MapViewLoaded -> {
                effect {
                    locationModel(LocationAction.RequestAccess)
                    locationModel(LocationAction.TryStartUpdatingLocation)
                }
                state
            }

            MapAction.External.Ui.LocationButtonTapped -> {
                val location = state.locationState.location
                when {
                    // Recenter, raising the zoom to LOCATION_ZOOM only when the camera is further
                    // out — never pulling it back (iOS's commented-out one-liner:
                    // `region.zoom = max(region.zoom, locationZoom)`). iOS measures the live map's
                    // span; our camera lives in Compose, so the reducer decides from its own mirror
                    // of it: the rounded zoom gates the raise (as iOS's integer
                    // `zoom(region:mapSize:)` does) and the raw camera zoom is what survives
                    // untouched when the camera is already closer in.
                    location != null ->
                        effect {
                            val zoom =
                                if (state.zoom < LOCATION_ZOOM) {
                                    LOCATION_ZOOM.toFloat()
                                } else {
                                    state.cameraZoom
                                }
                            focusCamera(CameraFocus(location, zoom))
                        }
                    // As on iOS, "not determined" and "denied" share one alert — the way out of
                    // both is the system settings page.
                    !state.locationState.isAccessGranted ->
                        effect { presentAlert(locationAccessDenied(openAppSettings)) }

                    else -> effect { presentAlert(unableToDetermineLocation) }
                }
                state
            }

            is MapAction.External.NewLocationState -> {
                val knownLocation = state.locationState.location
                val newLocation = action.locationState.location
                // The *first* fix recenters, once: the check reads the pre-mutation state, so it
                // must stay ahead of the copy below (iOS relies on the same ordering).
                if (newLocation != null && knownLocation == null) {
                    // Not animated, as on iOS: the user did not ask for this move, so it lands
                    // rather than flies.
                    effect {
                        focusCamera(CameraFocus(newLocation, FIRST_FIX_ZOOM, animated = false))
                    }
                }
                // A fix-less update (a provider hiccup, or an authorization-only change) must not
                // erase the last known location — iOS `$0.location = $0.location ?? state...`.
                state.copy(
                    locationState =
                        action.locationState.copy(location = newLocation ?: knownLocation),
                )
            }

            is MapAction.Internal.RegionChanged -> {
                asyncEffect(AsyncEffect.anotherAction(action = MapAction.Internal.LoadAnnotations))
                asyncEffect(
                    AsyncEffect.debounced(
                        id = DebouncedActionId.UpdatePreviews,
                        anotherAction = MapAction.Internal.UpdatePreviews,
                    ),
                )
                state
                    .copy(
                        region = action.region,
                        zoom = action.zoom,
                        cameraZoom = action.cameraZoom,
                    ).evictingFarAnnotations()
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

            MapAction.Internal.UpdatePreviews -> {
                // Skip entirely while a load is in flight, so previews never flash a half-loaded
                // region (iOS `guard !state.isLoading`); the load's `.Loaded` re-dispatches this.
                if (state.isLoading) {
                    state
                } else {
                    val images = regionImages(state.annotations, state.region, sorting())
                    state.copy(currentRegionImages = images, previews = makePreviews(images))
                }
            }

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

/**
 * iOS `AlertParams.locationAccessDenied(openSettings:)`, kept next to its only caller as there.
 * Both alerts are plain info (iOS attaches "Copy to clipboard" only to `.error`), and their copy
 * stays an English literal like every other reducer-raised alert — see M12 on why localising them
 * is a task of its own. The path in the message is iOS's wording; Android's own is close enough
 * ("Allow only while using the app"), and the button gets there in one tap anyway.
 */
private fun locationAccessDenied(openSettings: () -> Unit): AlertParams =
    AlertParams(
        title = "The app has no access to your location",
        message =
            "You can change it in Settings.\n" +
                "Go to Apps -> Rewind -> Location -> While Using the App",
        isError = false,
        action = AlertParams.Action(AlertParams.Action.Kind.OpenSettings, openSettings),
    )

/** iOS `AlertParams.unableToDetermineLocation`: access is there, a fix is not (yet). */
private val unableToDetermineLocation =
    AlertParams(
        title = "Unable to Determine Location",
        message = "Please try again later",
        isError = false,
    )
