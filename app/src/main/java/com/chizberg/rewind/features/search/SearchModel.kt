package com.chizberg.rewind.features.search

import com.chizberg.rewind.app.AlertParams
import com.chizberg.rewind.app.errorAlert
import com.chizberg.rewind.app.infoAlert
import com.chizberg.rewind.core.redux.AsyncEffect
import com.chizberg.rewind.core.redux.DebouncedActionId
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.Coordinate
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolves place suggestions and coordinates for the M12 search screen. Port of iOS
 * `SearchSuggestProvider` (`Screens/Search/SearchModel.swift`), but — unlike iOS, where it is
 * constructed *inside* `makeSearchModel` and is therefore untestable — deliberately pulled out
 * behind an interface so [makeSearchModel] can be exercised against a fake. This is a documented,
 * intentional Android/iOS divergence (see CLAUDE.md "Принятые расхождения", M12 entry): the real
 * implementation wraps Places (New) `findAutocompletePredictions` + `fetchPlace`, threading an
 * `AutocompleteSessionToken` across calls, and lives in `app/` (it needs a `Context`), not here.
 */
interface PlacesSuggestProvider {
    /** Autocomplete predictions for the current [query] text. */
    suspend fun suggestions(query: String): List<SearchState.Suggest>

    /**
     * Resolves a previously returned [suggest] to a coordinate (Places `fetchPlace` by its
     * `placeId`). Unlike iOS's `suggestSelected`, which reconstructs a text query from
     * `subtitle, title` and re-runs a full-text `MKLocalSearch`, the Android suggest already
     * carries a stable place id — no text reconstruction needed to pick the tapped place.
     */
    suspend fun resolve(suggest: SearchState.Suggest): Coordinate
}

/** State of the M12 search screen. Port of iOS `SearchState`. */
data class SearchState(
    val query: String = "",
    val suggests: List<Suggest> = emptyList(),
    val alert: AlertParams? = null,
) {
    /**
     * Port of iOS `SearchState.Suggest`, keyed by a Places `placeId` instead of an
     * `MKLocalSearchCompletion` — needed so [SearchAction.External.SuggestSelected] can
     * [PlacesSuggestProvider.resolve] the exact tapped place directly, rather than reconstructing
     * a `subtitle, title` text query the way iOS does.
     */
    data class Suggest(
        val placeId: String,
        val title: String,
        val subtitle: String,
    )
}

sealed interface SearchAction {
    /** Port of iOS `SearchAction.External` — the only surface the view/`ViewStore` sees. */
    sealed interface External : SearchAction {
        data class UpdateQuery(
            val query: String,
        ) : External

        data class SuggestSelected(
            val suggest: SearchState.Suggest,
        ) : External

        data class AddSuggestToQuery(
            val suggest: SearchState.Suggest,
        ) : External

        data object Submit : External

        data object DismissAlert : External
    }

    /** Port of iOS `SearchAction.Internal` — private to the reducer's own effects. */
    sealed interface Internal : SearchAction {
        data class SuggestsUpdated(
            val suggests: List<SearchState.Suggest>,
        ) : Internal

        data class SuggestsFailed(
            val error: Throwable,
        ) : Internal

        data class Resolved(
            val coordinate: Coordinate,
        ) : Internal

        data class ResolveFailed(
            val error: Throwable,
        ) : Internal

        /** Port of iOS `.nothingFound`: an *info* alert, not an error one. */
        data object NothingFound : Internal
    }
}

/** The search reducer. Port of iOS `SearchModel`. */
typealias SearchModel = Reducer<SearchState, SearchAction>

/**
 * The place lookup behind both "a suggest was tapped" and "the query was submitted". iOS gives each
 * of those a fresh random effect id, so two searches started in a row race and either may win
 * (a real, untested iOS bug — see the M12 notes); a stable id makes the newest request cancel the
 * older one, which is what "search again" means to the user.
 */
private const val RESOLVE_PLACE_ID = "searchResolvePlace"

/**
 * Builds a search reducer. Port of iOS `makeSearchModel(onLocationFound:)`.
 *
 * [onLocationFound] mirrors the iOS dependency 1:1 (`AppGraph` wires it to dismiss the search
 * overlay and emit a `focusRequests` camera focus at `SEARCH_FOCUS_ZOOM` — that literal and the
 * `AppGraph` wiring live outside this JVM-only reducer, see M12 notes). [suggestProvider] is the
 * Android-only injected dependency described on [PlacesSuggestProvider].
 *
 * Divergences from the iOS reducer, all forced by Places (New) replacing MapKit:
 * - **suggests are fetched by this reducer, not pushed at it.** iOS owns an `MKLocalSearchCompleter`
 *   that debounces internally and calls back through a signal; Places' autocomplete is a plain
 *   request, so [SearchAction.External.UpdateQuery] schedules it with
 *   [DebouncedActionId.SearchQueryChanged] and every keystroke cancels the pending one.
 * - **an emptied field clears the suggests.** iOS guards `!query.isEmpty` when forwarding the query
 *   to the completer, so the last non-empty query's suggests stay on screen and stay tappable after
 *   the user wipes the field — a stale list nothing on screen explains. Here the list goes with the
 *   text and the "Start typing" placeholder comes back.
 * - **a submit runs the same autocomplete and takes its first hit** (then resolves it), where iOS
 *   runs a second, full-text `MKLocalSearch` and takes `mapItems.first`. Same "top result for what
 *   you typed" semantics with one API and one injected dependency.
 * - **"nothing found" is an empty prediction list**, not iOS's `MKError.placemarkNotFound`; Places
 *   has no error code for it. Everything a request actually throws is the error alert.
 */
fun makeSearchModel(
    suggestProvider: PlacesSuggestProvider,
    onLocationFound: (Coordinate) -> Unit,
    scope: CoroutineScope,
): SearchModel =
    Reducer(SearchState(), scope) { state, action, effect, asyncEffect ->
        when (action) {
            is SearchAction.External.UpdateQuery -> {
                val query = action.query
                if (query.isBlank()) {
                    // Nothing to ask for, and a fetch already in flight for the erased text would
                    // land on an empty field: drop it and the list with it.
                    asyncEffect(AsyncEffect.cancel(DebouncedActionId.SearchQueryChanged))
                    state.copy(query = query, suggests = emptyList())
                } else {
                    asyncEffect(
                        AsyncEffect.debounced(DebouncedActionId.SearchQueryChanged) { send ->
                            send(fetchSuggests(suggestProvider, query))
                        },
                    )
                    state.copy(query = query)
                }
            }

            is SearchAction.External.SuggestSelected -> {
                val suggest = action.suggest
                asyncEffect(
                    AsyncEffect.perform(id = RESOLVE_PLACE_ID) { send ->
                        send(resolvePlace { suggestProvider.resolve(suggest) })
                    },
                )
                state
            }

            is SearchAction.External.AddSuggestToQuery ->
                state.copy(query = action.suggest.queryText)

            SearchAction.External.Submit -> {
                val query = state.query
                // An empty field has nothing to search for; iOS hands the empty string to
                // MKLocalSearch and shows whatever it errors with, which is not worth porting.
                if (query.isNotBlank()) {
                    asyncEffect(
                        AsyncEffect.perform(id = RESOLVE_PLACE_ID) { send ->
                            send(
                                resolvePlace {
                                    val top =
                                        suggestProvider.suggestions(query).firstOrNull()
                                            ?: return@resolvePlace null
                                    suggestProvider.resolve(top)
                                },
                            )
                        },
                    )
                }
                state
            }

            SearchAction.External.DismissAlert -> state.copy(alert = null)

            is SearchAction.Internal.SuggestsUpdated -> state.copy(suggests = action.suggests)

            is SearchAction.Internal.SuggestsFailed ->
                state.copy(
                    alert = errorAlert("Unable to load suggests for this query", action.error),
                )

            is SearchAction.Internal.Resolved -> {
                effect { onLocationFound(action.coordinate) }
                state
            }

            is SearchAction.Internal.ResolveFailed ->
                state.copy(
                    alert = errorAlert("Something went wrong during the search", action.error),
                )

            SearchAction.Internal.NothingFound ->
                state.copy(
                    alert =
                        infoAlert(
                            title = "Unable to find what you're looking for",
                            message = "Try to change the search query and try again",
                        ),
                )
        }
    }

/**
 * The suggest as text for the input field (iOS `Suggest.query`, used by `addSuggestToQuery`).
 *
 * Divergence: iOS joins `subtitle, title` — the wider context first, a trick for `MKLocalSearch`'s
 * natural-language parser, and the reverse of how the cell reads. Places' own full text for a
 * prediction is `primary, secondary` (= title, subtitle), i.e. exactly what the row shows, so the
 * text the button drops into the field is what the user just read.
 */
private val SearchState.Suggest.queryText: String
    get() = listOf(title, subtitle).filter { it.isNotEmpty() }.joinToString(", ")

/** One autocomplete round trip as the action it produces. */
private suspend fun fetchSuggests(
    provider: PlacesSuggestProvider,
    query: String,
): SearchAction =
    runCatchingCancellable(
        work = { SearchAction.Internal.SuggestsUpdated(provider.suggestions(query)) },
        onError = { SearchAction.Internal.SuggestsFailed(it) },
    )

/**
 * One place lookup as the action it produces: a coordinate, iOS's info alert when [lookup] finds
 * nothing (it returns null), or the error alert when the request itself fails.
 */
private suspend fun resolvePlace(lookup: suspend () -> Coordinate?): SearchAction =
    runCatchingCancellable(
        work = {
            lookup()?.let { SearchAction.Internal.Resolved(it) }
                ?: SearchAction.Internal.NothingFound
        },
        onError = { SearchAction.Internal.ResolveFailed(it) },
    )

/**
 * Runs [work], turning a failure into [onError]'s action — but never a cancellation: a superseded
 * fetch (a newer keystroke, a closed screen) is the reducer's own doing, not something to report.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun runCatchingCancellable(
    work: suspend () -> SearchAction,
    onError: (Throwable) -> SearchAction,
): SearchAction =
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    }
