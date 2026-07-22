package com.chizberg.rewind.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** One lenient JSON codec for every persisted preference. `ignoreUnknownKeys` lets a payload that
 * gained fields in a newer build still decode in an older one (mirrors iOS `decodeIfPresent`
 * tolerance); combined with default values on the DTO it also survives fields added later. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * Android analogue of iOS `Property.makeCodableField` (a `Property<T>` backed by a JSON-encoded
 * `UserDefaults` entry). Port of the M10 persistence primitive.
 *
 * [value] stays synchronous from the CALLER's perspective even though [dataStore] itself is not,
 * so `FavoritesModel` can persist through a plain synchronous `effect` exactly like iOS:
 *  - construction primes an in-memory cache with ONE `runBlocking` read (documented tradeoff:
 *    plan.md risk #5 — the first construction blocks its thread until the DataStore actor answers;
 *    on a cold start that is a brief hitch a splash/loading state would cover). The DataStore's own
 *    actor MUST run on a different scope than any caller that then blocks waiting on it, or the read
 *    deadlocks — in prod the actor lives on a background scope, in the test on `dataStoreScope`.
 *  - the getter returns that cache;
 *  - the setter updates the cache immediately (so a read-back in the same session is correct) and
 *    fires the actual persisted write on [scope] via `scope.launch { dataStore.edit { ... } }` —
 *    fire-and-forget, matching iOS's synchronous call SHAPE without literally blocking every write
 *    on disk I/O.
 *
 * [key] is the underlying `stringPreferencesKey` name (mirrors iOS's `UserDefaults` key, e.g.
 * `"favorites"`); the JSON payload for [T] is encoded/decoded through [serializer], and a missing
 * (or unparseable-as-missing) entry falls back to [defaultValue].
 */
class JsonPreference<T>(
    private val dataStore: DataStore<Preferences>,
    key: String,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    private val scope: CoroutineScope,
) {
    private val prefKey = stringPreferencesKey(key)

    private var cache: T =
        runBlocking {
            dataStore.data
                .first()[prefKey]
                ?.let { json.decodeFromString(serializer, it) }
                ?: defaultValue
        }

    var value: T
        get() = cache
        set(newValue) {
            cache = newValue
            val encoded = json.encodeToString(serializer, newValue)
            scope.launch {
                dataStore.edit { prefs -> prefs[prefKey] = encoded }
            }
        }
}
