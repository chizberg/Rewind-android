package com.chizberg.rewind.network

import com.chizberg.rewind.BuildConfig
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.StreetViewAvailability
import com.chizberg.rewind.network.dto.ByBoundsResponse
import com.chizberg.rewind.network.dto.GiveForPageResponse
import com.chizberg.rewind.network.dto.NetworkCluster
import com.chizberg.rewind.network.dto.NetworkImage
import com.chizberg.rewind.network.dto.NetworkImageDetails
import com.chizberg.rewind.network.dto.networkJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

/** Google's REST host for the Street View metadata lookup (key B, see
 *  `secrets.defaults.properties`). */
private const val GOOGLE_MAPS_API = "https://maps.googleapis.com/maps/api"

/** Cloud Translation v2 — the other half of key B. A host of its own, as on iOS. Public because
 *  the app layer tags requests to exactly this host with its Android-client headers (see
 *  `AndroidClientInterceptor`). */
const val TRANSLATION_API_HOST = "translation.googleapis.com"
private const val TRANSLATION_API = "https://$TRANSLATION_API_HOST/language/translate/v2"

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

/**
 * Street View metadata — is there a panorama at [coordinate], and from when.
 * Port of iOS `Network.streetViewAvailability`.
 * See https://developers.google.com/maps/documentation/streetview/metadata
 *
 * The quirk to keep: **any** status other than `OK` becomes
 * [StreetViewAvailability.Unavailable] — `ZERO_RESULTS`, `OVER_QUERY_LIMIT` and `REQUEST_DENIED`
 * are not told apart, so a spent quota reads to the user as "no panorama here". An unknown status
 * string is a decoding failure (as on iOS, whose `Status` enum only knows the seven documented
 * values), which the caller swallows — see the reducer's `viewWillAppear`.
 *
 * The key is read here rather than injected, mirroring iOS reading `Secrets.googleApiKey` inside
 * this very file.
 */
fun Request.Companion.streetViewAvailability(
    coordinate: Coordinate,
): Request<StreetViewAvailability> =
    Request(
        makeRequest = {
            val url =
                "$GOOGLE_MAPS_API/streetview/metadata"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter(
                        "location",
                        "${coordinate.latitude},${coordinate.longitude}",
                    ).addQueryParameter("key", BuildConfig.GOOGLE_REST_API_KEY)
                    .build()
            OkHttpRequest.Builder().url(url).build()
        },
        parseResult = { bytes ->
            val response =
                networkJson.decodeFromString<StreetViewMetadataResponse>(bytes.decodeToString())
            if (response.status == StreetViewMetadataResponse.Status.Ok) {
                StreetViewAvailability.Available(extractYear(response.date))
            } else {
                StreetViewAvailability.Unavailable
            }
        },
    )

@Serializable
private data class StreetViewMetadataResponse(
    val status: Status,
    /** `"YYYY-MM"` when a panorama exists; absent otherwise. */
    val date: String? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("OK")
        Ok,

        @SerialName("ZERO_RESULTS")
        ZeroResults,

        @SerialName("NOT_FOUND")
        NotFound,

        @SerialName("OVER_QUERY_LIMIT")
        OverQueryLimit,

        @SerialName("REQUEST_DENIED")
        RequestDenied,

        @SerialName("INVALID_REQUEST")
        InvalidRequest,

        @SerialName("UNKNOWN_ERROR")
        UnknownError,
    }
}

/**
 * The year out of the metadata's `"YYYY-MM"` date. Port of iOS `extractYear(date:)`: the first
 * `-`-separated piece, which has to be all digits — anything else (a missing date on an `OK`
 * response, a reformatted one) throws rather than guessing a year for the label.
 */
private fun extractYear(date: String?): Int {
    val year =
        date
            ?.trim()
            ?.split("-")
            ?.firstOrNull { it.isNotEmpty() }
            ?.takeIf { piece -> piece.all { it.isDigit() } }
            ?.toIntOrNull()
    return year ?: throw NetworkError.ParsingFailure(desc = "Invalid date format: $date")
}

/**
 * Cloud Translation v2 — one string into one language. Port of iOS `Network.translate(params:)`.
 * See https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate
 *
 * The first POST in this file, hence the hand-built JSON body (the other three are GETs with query
 * parameters). `Content-Type: application/json; charset=utf-8` rides on the body's media type,
 * which is what iOS sets by hand.
 *
 * The quirk to keep: [TranslateParams.text] is a PastVu *description*, i.e. raw HTML, and it is
 * still announced as `format: "text"` — verbatim from iOS. Google therefore escapes the markup
 * rather than translating around it, and the answer goes back through the same HTML parser the
 * original does. Not a bug to fix here: the rendered result is what both apps ship.
 */
fun Request.Companion.translate(params: TranslateParams): Request<String> =
    Request(
        makeRequest = {
            val url =
                TRANSLATION_API
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("key", BuildConfig.GOOGLE_REST_API_KEY)
                    .build()
            val body = TranslateBody(q = params.text, target = params.target)
            OkHttpRequest
                .Builder()
                .url(url)
                .post(paramsJson.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        },
        parseResult = { bytes ->
            val response =
                networkJson.decodeFromString<TranslateResponse>(bytes.decodeToString())
            response.data.translations
                .firstOrNull()
                ?.translatedText
                ?: throw NetworkError.ParsingFailure(desc = "No translations found")
        },
    )

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

@Serializable
private data class TranslateBody(
    val q: String,
    val target: String,
    /** Announced regardless of the HTML actually travelling in [q] — see the factory's comment. */
    val format: String = "text",
)

@Serializable
private data class TranslateResponse(
    val data: Data,
) {
    @Serializable
    data class Data(
        val translations: List<Translation>,
    )

    @Serializable
    data class Translation(
        val translatedText: String,
    )
}

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
