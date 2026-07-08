package com.chizberg.rewind.network

import com.chizberg.rewind.Fixture
import com.chizberg.rewind.domain.ImageRequestFilters
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * End-to-end proof of the production networking stack over real OkHttp against a local
 * MockWebServer: build request → HTTP → status handling → parse → DTO→domain map → backoff.
 * An interceptor reroutes the hardcoded api.pastvu.com host to the mock so the real request URL
 * is exercised unchanged. (The automated counterpart to the @Ignore live smoke.)
 */
class RewindRemotesIntegrationTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun remotesRoutedToMock(): RewindRemotes {
        val base = server.url("/")
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val rerouted =
                        original.url
                            .newBuilder()
                            .scheme(base.scheme)
                            .host(base.host)
                            .port(base.port)
                            .build()
                    chain.proceed(original.newBuilder().url(rerouted).build())
                }.build()
        return RewindRemotes(RequestPerformer(okHttpRequestPerformer(client)))
    }

    @Test
    fun annotationsLoadFetchesParsesAndMaps() =
        runBlocking {
            server.enqueue(MockResponse().setBody(Fixture.text("getByBounds_photos.json")))
            val remotes = remotesRoutedToMock()

            val (images, _) =
                remotes.annotations.load(
                    AnnotationLoadingParams(
                        zoom = 13,
                        coordinates = listOf(listOf(14.38, 50.09)),
                        startAt = 0.0,
                        filters = ImageRequestFilters.default,
                    ),
                )

            assertFalse(images.isEmpty())
            assertEquals(1_959_860, images[0].cid)
            // the outgoing request really carried the getByBounds method
            val recorded = server.takeRequest()
            assertEquals("photo.getByBounds", recorded.requestUrl?.queryParameter("method"))
        }
}
