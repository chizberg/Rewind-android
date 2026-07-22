package com.chizberg.rewind.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val KEY = "favorites"
private val SERIALIZER = ListSerializer(StorageImage.serializer())

/**
 * `JsonPreference` on a REAL DataStore over a tmp file — no Robolectric. This is the milestone's
 * manual check ("the heart survives `adb shell am kill`") pinned as an automated test.
 *
 * Catches two failure modes that are invisible from inside a single `JsonPreference` session and
 * that an ordinary code review would not flag (a synchronous-looking `value` getter looks correct
 * either way):
 *  - a setter that only updates its own in-memory cache and never actually reaches
 *    `dataStore.edit` (the underlying file would stay empty, and a second wrapper over the same
 *    `DataStore` would read back the default instead of what was supposedly "saved");
 *  - a primer that reads before a pending write has actually landed, silently falling back to the
 *    default.
 */
class JsonPreferenceTest {
    @Test
    fun writtenValueSurvivesAFreshWrapperOverTheSameDataStore() {
        val file = File.createTempFile("json-preference-test", ".preferences_pb")
        file.deleteOnExit()

        // The DataStore's OWN internal actor lives on this scope for as long as the DataStore
        // itself does -- it must stay separate from the scope handed to JsonPreference below,
        // whose children this test joins to wait for a write: joining the actor's scope would
        // hang forever (the actor never completes on its own).
        val dataStoreScope = CoroutineScope(Dispatchers.IO + Job())
        // JsonPreference's own dispatch scope for its fire-and-forget persistence writes.
        val writeScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(scope = dataStoreScope) { file }

            val img1 = storageImage(cid = 1, title = "A")
            val img2 = storageImage(cid = 2, title = "B")

            val writer =
                JsonPreference(
                    dataStore = dataStore,
                    key = KEY,
                    serializer = SERIALIZER,
                    defaultValue = emptyList(),
                    scope = writeScope,
                )
            writer.value = listOf(img1, img2)

            // Wait for the fire-and-forget persist this test's own scope launched -- a setter
            // that only touched the in-memory cache would leave nothing to join here, but this
            // test's later assertions (on the fresh wrapper) would still catch it.
            runBlocking {
                writeScope.coroutineContext.job.children
                    .forEach { it.join() }
            }

            assertEquals(listOf(img1, img2), writer.value)
            assertTrue("expected the DataStore file to have persisted bytes", file.length() > 0)

            // A brand-new JsonPreference wrapper over the SAME DataStore: its constructor primes
            // synchronously (no suspend call from this test), so `.value` must already be
            // correct right after construction.
            val reader =
                JsonPreference(
                    dataStore = dataStore,
                    key = KEY,
                    serializer = SERIALIZER,
                    defaultValue = emptyList(),
                    scope = writeScope,
                )
            assertEquals(listOf(img1, img2), reader.value)
        } finally {
            writeScope.cancel()
            dataStoreScope.cancel()
            file.delete()
        }
    }
}

private fun storageImage(
    cid: Int,
    title: String,
): StorageImage =
    StorageImage(
        cid = cid,
        imagePath = "$cid.jpg",
        title = title,
        dir = null,
        coordinate = Coordinate(latitude = 0.0, longitude = 0.0),
        date = ImageDate(year = 1900, year2 = 1900),
    )
