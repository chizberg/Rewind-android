package com.chizberg.rewind.features.map

import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.Coordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** The device-location reducer. Port of the iOS `LocationModel` typealias. */
typealias LocationModel = Reducer<LocationState, LocationAction>

/**
 * The device's location-tracking state. Port of iOS `LocationState`
 * (`Screens/Map/LocationModel.swift`).
 *
 * JVM-only: [location] is the domain [Coordinate], never `android.location.Location` — the Play
 * Services boundary lives entirely in the app-side [LocationSource] implementation, per the repo's
 * JVM-only rule.
 *
 * [errorMessage] mirrors iOS's field: written on a failed fix and never read by any consumer on
 * either platform (grep-confirmed dead on iOS) — kept for parity so both states read the same.
 */
data class LocationState(
    val location: Coordinate? = null,
    val errorMessage: String? = null,
    val isAccessGranted: Boolean = false,
) {
    companion object {
        val initial = LocationState()
    }
}

/**
 * Port of iOS `LocationAction`. The `locationEvent(_:)` case is expressed as a nested sealed
 * subtype rather than a wrapper case (the same shape [MapAction] uses), so events dispatch straight
 * into the reducer and `adding(source.events)` needs no re-wrapping.
 */
sealed interface LocationAction {
    /** iOS `.requestAccess` -> `manager.requestWhenInUseAuthorization()`. */
    data object RequestAccess : LocationAction

    /** iOS `.tryStartUpdatingLocation` -> `manager.startUpdatingLocation()`, access permitting. */
    data object TryStartUpdatingLocation : LocationAction

    /** Port of iOS `LocationAction.LocationEvent`: what the platform pushes back at us. */
    sealed interface LocationEvent : LocationAction {
        data class DidUpdateLocations(
            val locations: List<Coordinate>,
        ) : LocationEvent

        data class DidFailWithError(
            val error: Throwable,
        ) : LocationEvent

        /**
         * iOS passes a `CLAuthorizationStatus` and folds it to `isAuthorized` here; on Android the
         * fold ("either of the two location permissions is granted — precision is not
         * distinguished, as on iOS") happens at the UI edge that owns the permission launcher, so
         * only the verdict crosses into this JVM-only file.
         */
        data class DidChangeAuthorizationStatus(
            val isGranted: Boolean,
        ) : LocationEvent
    }
}

/**
 * The platform side of iOS's `CLLocationManager` + `LocationDelegate` pair, behind an interface so
 * the reducer stays JVM-only (same shape as M12's `PlacesSuggestProvider`). [events] is the
 * delegate's signal — subscribed once at construction; [requestAccess] and [startUpdatingLocation]
 * are the two manager calls the reducer makes.
 */
interface LocationSource {
    /** Everything the platform pushes back: fixes, failures, authorization changes. */
    val events: Flow<LocationAction.LocationEvent>

    /** Ask the user for access. */
    fun requestAccess()

    /** Start streaming fixes into [events]. Called only once access is granted. */
    fun startUpdatingLocation()
}

/**
 * Builds the location reducer. Port of iOS `makeLocationModel`, which owns the `CLLocationManager`
 * itself; here the platform is injected as [source] (the repo's DI-by-interface rule), and the
 * delegate signal arrives through `Reducer.adding` exactly as iOS's `.adding(signal:)`.
 *
 * Divergence from iOS, deliberate: iOS calls `manager.requestWhenInUseAuthorization()` *inline in
 * the reduce body* (its one place that skips `effect {}`); both manager calls go through `effect {}`
 * here, which is this codebase's convention and observably identical (fire-and-forget calls).
 */
fun makeLocationModel(
    source: LocationSource,
    scope: CoroutineScope,
): LocationModel =
    Reducer<LocationState, LocationAction>(
        initial = LocationState.initial,
        scope = scope,
    ) { state, action, effect, asyncEffect ->
        when (action) {
            LocationAction.RequestAccess -> {
                effect { source.requestAccess() }
                state
            }

            LocationAction.TryStartUpdatingLocation -> {
                if (state.isAccessGranted) effect { source.startUpdatingLocation() }
                state
            }

            is LocationAction.LocationEvent.DidUpdateLocations ->
                // iOS `state.location = locations.last` — an empty batch clears it, and the map's
                // `newLocationState` merge is what keeps the last known fix (see makeMapModel).
                state.copy(location = action.locations.lastOrNull())

            is LocationAction.LocationEvent.DidFailWithError ->
                state.copy(errorMessage = action.error.toString())

            is LocationAction.LocationEvent.DidChangeAuthorizationStatus -> {
                // Access just appeared -> start tracking. Async, so it reads the state committed
                // below (iOS asyncEffect(.anotherAction(.tryStartUpdatingLocation))).
                if (action.isGranted) {
                    asyncEffect(
                        AsyncEffect.anotherAction(
                            action = LocationAction.TryStartUpdatingLocation,
                        ),
                    )
                }
                state.copy(isAccessGranted = action.isGranted)
            }
        }
    }.adding(source.events) { it }
