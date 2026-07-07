package com.chizberg.rewind.network.dto

import com.chizberg.rewind.Fixture
import com.chizberg.rewind.domain.ModelCluster
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of iOS NetworkParsingTests. Decodes the recorded fixtures into the DTOs and maps them
 * into the domain layer, pinning: the CodingKey remaps (p/c/geo, disp), the [lat,lon] geo order,
 * the garbage-query `file`, nullable vs empty arrays, and the preview `.reversed()` quirk.
 */
private const val TOL = 1e-9

class NetworkParsingTest {
    private fun decodeBounds(name: String): ByBoundsResponse.ClusteredImages =
        networkJson.decodeFromString<ByBoundsResponse>(Fixture.text(name)).result

    private fun decodeDetails(name: String): NetworkImageDetails =
        networkJson.decodeFromString<GiveForPageResponse>(Fixture.text(name)).result.photo

    // Network.Image decoding

    @Test
    fun photosFixtureDecodes() {
        // Each photo also carries an unknown "__v" key; a successful decode proves the
        // decoder ignores it (ignoreUnknownKeys), so no separate test for that.
        val result = decodeBounds("getByBounds_photos.json")
        assertFalse(result.photos.isNullOrEmpty())
        assertNull(result.clusters) // key absent in this fixture
    }

    @Test
    fun firstPhotoFieldsAndGarbageQueryPreserved() {
        val first = decodeBounds("getByBounds_photos.json").photos!![0]
        assertEquals(1_959_860, first.cid)
        // The `?s=...` garbage query param must survive verbatim into `file`.
        assertEquals("q/q/p/qqp52d1i1jn4qndllt.jpg?s=81293f61a6", first.file)
        assertEquals("nw", first.dir)
        assertEquals(1890, first.year)
        assertEquals(1895, first.year2)
        // geo is [lat, lon].
        assertEquals(50.089992, first.geo[0], TOL)
        assertEquals(14.419038, first.geo[1], TOL)
    }

    @Test
    fun missingDirDecodesToNull() {
        val third = decodeBounds("getByBounds_photos.json").photos!![2]
        assertEquals(1_856_884, third.cid)
        assertNull(third.dir) // no "dir" key in JSON
    }

    // Network.Cluster decoding (p/c/geo keys)

    @Test
    fun clustersFixtureDecodes() {
        val result = decodeBounds("getByBounds_clusters.json")
        assertTrue(result.photos!!.isEmpty())
        assertFalse(result.clusters.isNullOrEmpty())
    }

    @Test
    fun firstClusterMapsPreviewAndCountKeys() {
        val cluster = decodeBounds("getByBounds_clusters.json").clusters!![0]
        // "c" -> count, "p" -> preview, "geo" is [lat,lon] for the cluster itself.
        assertEquals(83, cluster.count)
        assertEquals(50.072674, cluster.geo[0], TOL)
        assertEquals(14.443844, cluster.geo[1], TOL)
        assertEquals(2_081_180, cluster.preview.cid)
        // The preview's raw geo is [lon, lat] (REVERSED) as the server sends it.
        assertEquals(14.444176, cluster.preview.geo[0], TOL)
        assertEquals(50.072229, cluster.preview.geo[1], TOL)
    }

    // Nullable-arrays quirk

    @Test
    fun emptyResultFixtureHasEmptyArrays() {
        val result = decodeBounds("getByBounds_empty.json")
        assertTrue(result.photos!!.isEmpty())
        assertTrue(result.clusters!!.isEmpty())
    }

    @Test
    fun absentArraysDecodeToNull() {
        val result = networkJson.decodeFromString<ByBoundsResponse>("""{"result":{}}""").result
        assertNull(result.photos)
        assertNull(result.clusters)
    }

    // Network.ImageDetails decoding (disp key)

    @Test
    fun detailsDecodesUserDispKey() {
        val details = decodeDetails("imageDetails_watersign.json")
        assertEquals(1_641_494, details.cid)
        assertEquals("Теразије", details.title)
        assertEquals("Николай", details.user.name) // via @SerialName("disp")
        assertEquals("uploaded by nb92", details.watersignText)
        // The HTML <a href=...> source is kept as-is.
        assertTrue(details.source!!.contains("<a href="))
        assertEquals(44.813047, details.geo[0], TOL)
        assertEquals(20.460579, details.geo[1], TOL)
    }

    // Model.Cluster mapping (reversed preview coordinate quirk)

    @Test
    fun modelClusterReversesPreviewCoordinateOnly() {
        val nc = decodeBounds("getByBounds_clusters.json").clusters!![0]
        val cluster = ModelCluster(nc)
        // Cluster's own coordinate is NOT reversed: [lat, lon] straight through.
        assertEquals(50.072674, cluster.coordinate.latitude, TOL)
        assertEquals(14.443844, cluster.coordinate.longitude, TOL)
        // Preview coordinate IS reversed: server sent [lon, lat] -> flipped to (lat, lon).
        assertEquals(50.072229, cluster.preview.coordinate.latitude, TOL)
        assertEquals(14.444176, cluster.preview.coordinate.longitude, TOL)
    }
}
