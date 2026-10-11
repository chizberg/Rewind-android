package com.chizberg.rewind.network

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Mirror of iOS `ImageDetailsLoaderTests`: details fetched once are reused for every later load of
 * the same photo, and distinct photos don't share an entry. The stub network echoes the requested
 * cid back and stamps every response with its fetch number, so a cached value is distinguishable
 * from a refetched one.
 *
 * Not mirrored (rule 3 — a map lookup and a `clear()` that read in seconds): iOS's
 * `clearCacheForcesRefetch`, `cachedIsReadableWithoutLoading`, `failedLoadIsNotCached`.
 */
class ImageDetailsLoaderTest {
    /** Loading the same photo twice performs a single request; the second load is served from
     * cache. */
    @Test
    fun repeatedLoadIsServedFromCache() =
        runTest {
            val network = NetworkStub()
            val loader = ImageDetailsLoader(RequestPerformer(network::respond))

            val first = loader.load(42)
            val second = loader.load(42)

            assertEquals(listOf(42), network.requestedCids)
            assertEquals(42, first.cid)
            assertEquals("fetch #1", first.title)
            assertEquals("fetch #1", second.title) // not refetched, hence not "fetch #2"
        }

    /** Every photo gets its own cache entry: two photos are fetched once each and never mixed up. */
    @Test
    fun detailsAreCachedPerImage() =
        runTest {
            val network = NetworkStub()
            val loader = ImageDetailsLoader(RequestPerformer(network::respond))

            val first = loader.load(1)
            val second = loader.load(2)
            val firstAgain = loader.load(1)
            val secondAgain = loader.load(2)

            assertEquals(listOf(1, 2), network.requestedCids)
            assertEquals(1, firstAgain.cid)
            assertEquals(first.title, firstAgain.title)
            assertEquals(2, secondAgain.cid)
            assertEquals(second.title, secondAgain.title)
            assertNotEquals(first.title, second.title)
        }
}

/** Stands in for the PastVu API: records the cid of every request and answers with details for
 * that cid, titled with the number of the fetch that produced them. */
private class NetworkStub {
    val requestedCids = mutableListOf<Int>()
    private var fetchCount = 0

    fun respond(request: Request): Pair<ByteArray, Int> {
        val params = request.url.queryParameter("params")!!
        val cid = Regex("\"cid\":(\\d+)").find(params)!!.groupValues[1].toInt()
        requestedCids += cid
        fetchCount += 1
        return detailsJson(cid, "fetch #$fetchCount").toByteArray() to 200
    }
}

private fun detailsJson(
    cid: Int,
    title: String,
): String =
    """
    {"result": {"photo": {
      "cid": $cid, "file": "a/b/c.jpg", "title": "$title",
      "geo": [44.813047, 20.460579], "year": 1958, "year2": 1965,
      "h": 800, "waterh": 21,
      "ldate": "2022-09-30T16:52:26.687Z", "user": {"disp": "Николай"}
    }}}
    """.trimIndent()
