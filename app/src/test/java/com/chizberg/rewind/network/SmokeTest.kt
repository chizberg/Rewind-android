package com.chizberg.rewind.network

import com.chizberg.rewind.domain.ImageRequestFilters
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Manual smoke against the live api.pastvu.com. Run explicitly; excluded from CI. */
@Ignore("manual: hits the live PastVu API")
class SmokeTest {
    @Test
    fun byBoundsPragueReturnsAnnotations() =
        runBlocking {
            val remotes = RewindRemotes(RequestPerformer(okHttpRequestPerformer(OkHttpClient())))
            val (photos, clusters) =
                remotes.annotations.load(
                    AnnotationLoadingParams(
                        zoom = 13,
                        coordinates =
                            listOf(
                                listOf(14.38, 50.09),
                                listOf(14.45, 50.09),
                                listOf(14.45, 50.06),
                                listOf(14.38, 50.06),
                                listOf(14.38, 50.09),
                            ),
                        startAt = 0.0,
                        filters = ImageRequestFilters.default,
                    ),
                )
            assertTrue(photos.isNotEmpty() || clusters.isNotEmpty())
        }
}
