package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.MapType
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.network.AnnotationLoadingParams

/**
 * Actions for [makeMapModel]. Port of iOS `MapAction`, trimmed to the region loading + filters +
 * controls + location + map-type scope; annotation selection is handled at the UI boundary here (a
 * tap picks its route in `RewindMap`, since the camera lives in Compose), not by the reducer.
 * The External.Map / External.Ui / Internal nesting mirrors iOS so both codebases read in parallel.
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

            /**
             * The finger moved while panning the map (iOS `userDragged(CGPoint, CGRect)`, fed by a
             * `UIPanGestureRecognizer` on the map view itself; here by an observing `pointerInput`
             * around the map — see `RewindMap`). Both values are in dp, and both are raw: the
             * reducer, not the view, decides whether the touch is inside the controls' band.
             *
             * Fires on every movement of the gesture, exactly as iOS's `handlePan` does.
             */
            data class UserDragged(
                val touchY: Float,
                val viewportHeight: Float,
            ) : Map
        }

        sealed interface Ui : External {
            /**
             * The map surface finished loading (iOS `mapViewLoaded`, fired by `MKMapView`'s
             * delegate; here by `GoogleMap`'s `onMapLoaded`). Kicks off the location permission
             * request and tracking.
             */
            data object MapViewLoaded : Ui

            /** The floating menu's location glyph was tapped (iOS `locationButtonTapped`). */
            data object LocationButtonTapped : Ui

            data class FiltersChanged(
                val filters: ImageRequestFilters,
            ) : Ui

            /** The floating menu's scheme/satellite toggle (iOS `mapTypeSelected`). */
            data class MapTypeSelected(
                val mapType: MapType,
            ) : Ui

            /** Port of iOS `MapAction.External.UI.controls`. */
            sealed interface Controls : Ui {
                data class SetExpandedItems(
                    val items: Set<MapControlItem>,
                ) : Controls

                /**
                 * The user folded or unfolded the controls by hand — dragging the strip down/up, or
                 * tapping a minimized strip to bring it back (iOS `setMinimization`).
                 */
                data class SetMinimization(
                    val minimization: Minimization,
                ) : Controls

                /**
                 * The strip measured itself (iOS `sizeChanged`, which carries the whole `CGSize`;
                 * only the height is ever read). See `ControlsState.heightDp`.
                 */
                data class SizeChanged(
                    val heightDp: Float,
                ) : Controls
            }
        }

        /**
         * The location reducer's state, streamed in from `AppGraph`'s single location model
         * instance via `Reducer.adding`. Port of iOS `MapAction.External.newLocationState`.
         */
        data class NewLocationState(
            val locationState: LocationState,
        ) : External
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

        /** The 2s debounce after an automatic minimize expired (iOS `unfoldMapControlsBack`). */
        data object UnfoldMapControlsBack : Internal

        data object ClearAnnotations : Internal
    }
}
