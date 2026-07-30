package com.chizberg.rewind.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.features.map.LocationAction.LocationEvent
import com.chizberg.rewind.features.map.LocationSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** How often a fresh fix is asked for, in ms. iOS tracks continuously (`distanceFilter` none). */
private const val UPDATE_INTERVAL_MS = 5_000L

/**
 * The Play Services side of the location feature: iOS's `CLLocationManager` + its delegate, behind
 * the JVM-only [LocationSource] the reducer sees (the M12 `GooglePlacesSuggestProvider` shape).
 * One instance per graph — location tracking is a map-lifetime concern, not a per-screen one, so
 * unlike the Places provider it is never rebuilt.
 *
 * Android-only wrinkle: `requestWhenInUseAuthorization()` has no headless equivalent — the
 * runtime dialog needs an Activity result launcher, which lives in composition. [requestAccess]
 * therefore only rings [permissionRequests]; the UI-side host answers it and reports the verdict
 * back as a `DidChangeAuthorizationStatus` event (see `LocationPermissionHost`).
 */
class FusedLocationSource(
    context: Context,
    private val scope: CoroutineScope,
) : LocationSource {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    // Hot, like iOS's SignalPipe: the reducer subscribes once at construction and every later fix
    // is pushed at it. DROP_OLDEST because only the newest fix matters if anything ever backs up.
    private val eventsMutable =
        MutableSharedFlow<LocationEvent>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val events: Flow<LocationEvent> = eventsMutable

    private val permissionRequestsMutable =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Rings whenever the reducer asks for access; the UI host runs the system dialog. */
    val permissionRequests: SharedFlow<Unit> = permissionRequestsMutable

    private var updates: Job? = null

    override fun requestAccess() {
        permissionRequestsMutable.tryEmit(Unit)
    }

    override fun startUpdatingLocation() {
        // Idempotent: the map reports `mapViewLoaded` again after an activity recreation, and every
        // resume re-reports the authorization status, so this arrives more than once per process.
        if (updates != null) return
        emitLastKnownLocation()
        val job =
            scope.launch {
                locationUpdates()
                    .catch { eventsMutable.tryEmit(LocationEvent.DidFailWithError(it)) }
                    .collect {
                        eventsMutable.tryEmit(LocationEvent.DidUpdateLocations(it))
                    }
            }
        job.invokeOnCompletion { updates = null }
        updates = job
    }

    /**
     * `CLLocationManager` delivers its cached fix almost as soon as tracking starts, which is what
     * makes the iOS map recenter the moment it opens; Fused waits for a fresh one (seconds, indoors
     * potentially much longer), so the cached fix is asked for explicitly to keep that behaviour.
     *
     * Reached only once `isAccessGranted`; a permission revoked meanwhile lands in the runCatching.
     */
    @SuppressLint("MissingPermission")
    private fun emitLastKnownLocation() {
        runCatching {
            client.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    eventsMutable.tryEmit(
                        LocationEvent.DidUpdateLocations(listOf(it.toCoordinate())),
                    )
                }
            }
        }
    }

    /**
     * The fix stream. `callbackFlow` so cancelling the collection unregisters the callback for us
     * (iOS never stops its manager either, but the graph's scope dying must not leak one).
     * Collected only from [startUpdatingLocation], i.e. once access is granted.
     */
    @SuppressLint("MissingPermission")
    private fun locationUpdates(): Flow<List<Coordinate>> =
        callbackFlow {
            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        trySend(result.locations.map(Location::toCoordinate))
                    }
                }
            val request =
                LocationRequest
                    .Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                    .build()
            try {
                client
                    .requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnFailureListener { close(it) }
            } catch (e: SecurityException) {
                // Access revoked between the check and the call: report it, don't crash.
                close(e)
            }
            awaitClose { client.removeLocationUpdates(callback) }
        }
}

/** The one place `android.location.Location` becomes the domain [Coordinate]. */
private fun Location.toCoordinate(): Coordinate =
    Coordinate(latitude = latitude, longitude = longitude)
