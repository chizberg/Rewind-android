package com.chizberg.rewind.network

import com.chizberg.rewind.network.dto.ByBoundsResponse
import com.chizberg.rewind.network.dto.GiveForPageResponse
import com.chizberg.rewind.network.dto.NetworkCluster
import com.chizberg.rewind.network.dto.NetworkImage
import com.chizberg.rewind.network.dto.NetworkImageDetails
import com.chizberg.rewind.network.dto.networkJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request as OkHttpRequest

/**
 * A typed HTTP request: how to build it and how to parse its response bytes. Port of iOS
 * `Network.Request<Response>` (`makeRequest` ~ `makeURLRequest`, `parseResult` takes the body).
 * Factories live as `Request.Companion` extensions below.
 */
class Request<Response>(
    val makeRequest: () -> OkHttpRequest,
    val parseResult: (ByteArray) -> Response,
) {
    companion object
}

private const val PASTVU_API = "https://api.pastvu.com/api2"

private fun isLocalWork(zoom: Int): Boolean = zoom >= 17

// encodeDefaults so geometry.type = "Polygon" is emitted (kotlinx omits defaults by default).
private val paramsJson = Json { encodeDefaults = true }

@Serializable
private data class ByBoundsParams(
    val z: Int,
    val year: Int,
    val year2: Int,
    val isPainting: Boolean,
    val localWork: Boolean,
    val geometry: Geometry,
    val startAt: Double,
) {
    @Serializable
    data class Geometry(
        val coordinates: List<List<List<Double>>>,
        val type: String = "Polygon",
    )
}

/** `photo.getByBounds`. Port of iOS `Network.byBounds`; returns the raw DTO pair. */
fun Request.Companion.byBounds(
    zoom: Int,
    coordinates: List<List<Double>>,
    startAt: Double,
    yearRange: IntRange,
    isPainting: Boolean,
): Request<Pair<List<NetworkImage>, List<NetworkCluster>>> =
    Request(
        makeRequest = {
            val params =
                ByBoundsParams(
                    z = zoom,
                    year = yearRange.first,
                    year2 = yearRange.last,
                    isPainting = isPainting,
                    localWork = isLocalWork(zoom),
                    // GeoJSON Polygon coordinates = array of linear rings, so wrap the ring once.
                    geometry = ByBoundsParams.Geometry(coordinates = listOf(coordinates)),
                    startAt = startAt,
                )
            val url =
                PASTVU_API
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("method", "photo.getByBounds")
                    .addQueryParameter("params", paramsJson.encodeToString(params))
                    .build()
            OkHttpRequest.Builder().url(url).build()
        },
        parseResult = { bytes ->
            val response = networkJson.decodeFromString<ByBoundsResponse>(bytes.decodeToString())
            (response.result.photos ?: emptyList()) to (response.result.clusters ?: emptyList())
        },
    )

/** `photo.giveForPage`. Port of iOS `Network.imageDetails`. */
fun Request.Companion.imageDetails(cid: Int): Request<NetworkImageDetails> =
    Request(
        makeRequest = {
            val url =
                PASTVU_API
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("method", "photo.giveForPage")
                    .addQueryParameter("params", """{"cid":$cid}""")
                    .build()
            OkHttpRequest.Builder().url(url).build()
        },
        parseResult = { bytes ->
            networkJson.decodeFromString<GiveForPageResponse>(bytes.decodeToString()).result.photo
        },
    )
