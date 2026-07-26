package com.chizberg.rewind.app

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.features.search.PlacesSuggestProvider
import com.chizberg.rewind.features.search.SearchState
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real [PlacesSuggestProvider]: Places (New) autocomplete for the suggests, `fetchPlace` for the
 * coordinate. Android's stand-in for iOS `MKLocalSearchCompleter` + `MKLocalSearch`, which have no
 * equivalent of the billing session this class has to keep.
 *
 * **Session token lifetime is the whole point of holding state here.** Google bills a session — the
 * keystroke-by-keystroke autocomplete calls plus the one `fetchPlace` that closes it — as a single
 * request, but only when every call carries the same token and a *new* token is minted afterwards.
 * Reusing a spent session's token bills each later call on its own; never minting a new one keeps
 * charging calls against a session that already ended. So [suggestions] opens a session on demand
 * and [resolve] spends it and clears it.
 *
 * One instance per search screen (iOS builds its `SearchSuggestProvider` inside `makeSearchModel`
 * the same way), so a screen closed mid-typing takes its unfinished session with it; the [client] is
 * shared, being the expensive part. Confined to the reducer's main-thread scope like every model
 * here, so the plain `var` needs no synchronization.
 *
 * No location bias: iOS gives neither the completer nor the search a region, so results are the
 * same worldwide ones there and here. Biasing to the visible map would be a behaviour change, not a
 * port.
 */
class GooglePlacesSuggestProvider(
    private val client: PlacesClient,
) : PlacesSuggestProvider {
    private var sessionToken: AutocompleteSessionToken? = null

    override suspend fun suggestions(query: String): List<SearchState.Suggest> {
        val token =
            sessionToken
                ?: AutocompleteSessionToken.newInstance().also { sessionToken = it }
        val response =
            awaitCancellable { cancellation ->
                client.findAutocompletePredictions(
                    FindAutocompletePredictionsRequest
                        .builder()
                        .setQuery(query)
                        .setSessionToken(token)
                        .setCancellationToken(cancellation.token)
                        .build(),
                )
            }
        return response.autocompletePredictions.map { prediction ->
            SearchState.Suggest(
                placeId = prediction.placeId,
                // The spans only carry match highlighting, which the list doesn't draw.
                title = prediction.getPrimaryText(null).toString(),
                subtitle = prediction.getSecondaryText(null).toString(),
            )
        }
    }

    override suspend fun resolve(suggest: SearchState.Suggest): Coordinate {
        // Whatever comes back, this session is over: the fetch that closes it carries the token, and
        // the next keystroke opens a fresh one.
        val token = sessionToken
        sessionToken = null
        val response =
            awaitCancellable { cancellation ->
                client.fetchPlace(
                    FetchPlaceRequest
                        .builder(suggest.placeId, listOf(Place.Field.LOCATION))
                        .apply { token?.let { setSessionToken(it) } }
                        .setCancellationToken(cancellation.token)
                        .build(),
                )
            }
        return response.place.location?.let {
            Coordinate(latitude = it.latitude, longitude = it.longitude)
        } ?: throw IOException("Place ${suggest.placeId} came back without a location")
    }
}

/**
 * Awaits a Play-services [Task] as a suspending call and cancels the request itself when the
 * coroutine is cancelled — which the search field's debounce does on every keystroke, so an
 * abandoned autocomplete is neither waited on nor billed. [call] receives the token source to hand
 * to its request builder.
 */
private suspend fun <T> awaitCancellable(call: (CancellationTokenSource) -> Task<T>): T {
    val cancellation = CancellationTokenSource()
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancellation.cancel() }
        call(cancellation)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
            .addOnCanceledListener { continuation.cancel() }
    }
}
