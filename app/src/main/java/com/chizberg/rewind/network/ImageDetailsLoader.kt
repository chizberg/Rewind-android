package com.chizberg.rewind.network

import com.chizberg.rewind.domain.ModelImageDetails
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of `photo.giveForPage` responses. Port of iOS `ImageDetailsLoader`: details are
 * fetched once per photo, so reopening it (or coming back to it through a description link) is
 * instant. A failed load caches nothing.
 *
 * iOS confines the cache to the main actor; ours is read by the graph on main but filled from
 * whatever context the remote resumes on, so the map is a concurrent one instead.
 */
class ImageDetailsLoader(
    private val requestPerformer: RequestPerformer,
) {
    private val cache = ConcurrentHashMap<Int, ModelImageDetails>()

    /** Drops every cached response. Called on memory pressure (iOS: memory warnings). */
    fun clearCache() {
        cache.clear()
    }

    /** What the details screen seeds its first state with — no suspension, no request. */
    fun cached(cid: Int): ModelImageDetails? = cache[cid]

    suspend fun load(cid: Int): ModelImageDetails =
        cached(cid)
            ?: ModelImageDetails(requestPerformer.perform(Request.imageDetails(cid))).also {
                cache[cid] = it
            }
}
