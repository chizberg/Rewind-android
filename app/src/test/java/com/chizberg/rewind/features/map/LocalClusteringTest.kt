package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageDate
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelLocalCluster
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.network.AnnotationLoadingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Mirror of iOS `LocalClusteringTests.swift`: the grid clustering diff [makeDiffAfterReceived].
 * The algorithm derives the cell from the visible longitude span (`region.span / 8`), so [receive]
 * sets `region.span` per the load's zoom (as the reducer's `regionChanged` does), and the fixture
 * places images at cell centers using the same [cellSize]. `spanForZoom` keeps the old per-zoom
 * scale so the relative assertions (e.g. zoom 13 vs 10 → ×8 cell) are unchanged.
 *
 * Divergence from iOS: the local-cluster threshold is 2 (any cell past a single image clusters),
 * not iOS's 5 — Android has no overlap layer, so the grid is aggressive. The boundary is asserted
 * behaviorally: 1 image stays individual, 2 form a cluster.
 */
class LocalClusteringTest {
    // MARK: Cluster formation (fresh state, first load → no clearing)

    @Test
    fun singleImageStaysIndividual() {
        var state = emptyState()
        val r = receive(state, cellImages(1, zoom = 13), params = params(zoom = 13))
        state = r.state

        assertEquals(1, r.toAdd.images.size)
        assertTrue(r.toAdd.localClusters.isEmpty())
        assertTrue(r.toRemove.isEmpty())
        assertEquals(1, state.clusteredImages.size)
        assertEquals(
            1,
            state.clusteredImages.values
                .first()
                .left
                ?.size,
        )
    }

    @Test
    fun twoImagesInACellFormCluster() {
        var state = emptyState()
        val r = receive(state, cellImages(2, zoom = 13), params = params(zoom = 13))
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size)
        assertEquals(
            2,
            r.toAdd.localClusters
                .first()
                .images.size,
        )
        assertTrue(r.toAdd.images.isEmpty())
        assertTrue(r.toRemove.isEmpty())
        assertEquals(1, state.clusteredImages.size)
        assertNotNull(
            state.clusteredImages.values
                .first()
                .right,
        )
    }

    @Test
    fun cellsClusterIndependently() {
        var state = emptyState()
        // 6 images in cell (0,0) → cluster; 1 in cell (5,5) → individual.
        val images =
            cellImages(6, cellLat = 0, cellLon = 0, from = 0, zoom = 13) +
                cellImages(1, cellLat = 5, cellLon = 5, from = 100, zoom = 13)
        val r = receive(state, images, params = params(zoom = 13))
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size)
        assertEquals(
            6,
            r.toAdd.localClusters
                .first()
                .images.size,
        )
        assertEquals(1, r.toAdd.images.size)
        assertEquals(2, state.clusteredImages.size)
        assertTrue(state.clusteredImages.values.any { it.right != null })
        assertTrue(state.clusteredImages.values.any { it.left?.size == 1 })
    }

    // MARK: Incremental growth (same zoom & filters → additive path)

    @Test
    fun onePlusOnePromotesToCluster() {
        var state = emptyState()
        val p = params(zoom = 13)
        val base = cellImages(1, from = 0, zoom = 13)
        state = receive(state, base, params = p).state // 1 individual

        // Next load returns the original 1 plus 1 new one (2 total in the cell → over threshold).
        val grown = base + cellImages(1, from = 100, zoom = 13)
        val r = receive(state, grown, params = p)
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size)
        assertEquals(
            2,
            r.toAdd.localClusters
                .first()
                .images.size,
        )
        assertTrue(r.toAdd.images.isEmpty())
        // The previously-individual annotation is removed in favor of the cluster.
        assertEquals(1, r.toRemove.images.size)
        assertNotNull(
            state.clusteredImages.values
                .first()
                .right,
        )
    }

    @Test
    fun newImageAddedToExistingClusterGetsNewIdentity() {
        var state = emptyState()
        val p = params(zoom = 13)
        val base = cellImages(5, from = 0, zoom = 13)
        val first = receive(state, base, params = p)
        state = first.state
        val oldCluster = first.toAdd.localClusters.first()

        val grown = base + cellImages(1, from = 100, zoom = 13) // 6 in the cell
        val r = receive(state, grown, params = p)
        state = r.state

        val newCluster = r.toAdd.localClusters.first()
        assertEquals(6, newCluster.images.size)
        assertTrue(newCluster.id != oldCluster.id) // re-created with a fresh id
        assertEquals(listOf(oldCluster.id), r.toRemove.localClusters.map { it.id })
        assertTrue(r.toAdd.images.isEmpty())
    }

    @Test
    fun redundantReloadOfClusterIsNoOp() {
        var state = emptyState()
        val p = params(zoom = 13)
        val images = cellImages(5, zoom = 13)
        state = receive(state, images, params = p).state

        val r = receive(state, images, params = p)
        assertTrue(r.toAdd.isEmpty())
        assertTrue(r.toRemove.isEmpty())
    }

    @Test
    fun redundantReloadOfIndividualsIsNoOp() {
        var state = emptyState()
        val p = params(zoom = 13)
        val images = cellImages(1, zoom = 13) // one image → stays individual
        state = receive(state, images, params = p).state

        val r = receive(state, images, params = p)
        assertTrue(r.toAdd.isEmpty())
        assertTrue(r.toRemove.isEmpty())
    }

    @Test
    fun clusterDoesNotBreakApartWithFewerImagesAtSameZoom() {
        // Answers "is breaking apart even possible?": at the same zoom, no.
        // The non-clear path is purely additive — a subset reload changes nothing.
        var state = emptyState()
        val p = params(zoom = 13)
        val full = cellImages(6, zoom = 13)
        state = receive(state, full, params = p).state

        val subset = full.take(3)
        val r = receive(state, subset, params = p)
        state = r.state

        assertTrue(r.toAdd.isEmpty())
        assertTrue(r.toRemove.isEmpty())
        assertEquals(
            6,
            state.clusteredImages.values
                .first()
                .right
                ?.images
                ?.size,
        )
    }

    // MARK: Zoom change (clear + rebuild)

    @Test
    fun zoomChangeRebuildsClusterWithNewIdentity() {
        var state = emptyState()
        // Identical coordinates → the 5 stay together in one cell at any zoom.
        val images = cellImages(5, zoom = 10)
        val first = receive(state, images, params = params(zoom = 10))
        state = first.state
        val oldCluster = first.toAdd.localClusters.first()

        val r = receive(state, images, params = params(zoom = 11))
        state = r.state
        val newCluster = r.toAdd.localClusters.first()

        assertEquals(listOf(oldCluster.id), r.toRemove.localClusters.map { it.id })
        assertEquals(5, newCluster.images.size)
        assertTrue(newCluster.id != oldCluster.id)
    }

    @Test
    fun zoomInBreaksClusterIntoIndividuals() {
        var state = emptyState()
        // Cell centers at zoom 13 in 5 distinct rows. At zoom 10 (cell size ×8) they all
        // collapse into one cell → a cluster; back at zoom 13 they split into 5 cells.
        val images = (0 until 5).map { img(it, cellLat = it, cellLon = 0, zoom = 13) }
        val first = receive(state, images, params = params(zoom = 10))
        state = first.state
        assertEquals(1, first.toAdd.localClusters.size) // clustered while zoomed out

        val r = receive(state, images, params = params(zoom = 13))
        state = r.state
        assertEquals(1, r.toRemove.localClusters.size) // old cluster removed
        assertEquals(5, r.toAdd.images.size) // re-evaluated as 5 individuals
        assertTrue(r.toAdd.localClusters.isEmpty())
        assertEquals(5, state.clusteredImages.size)
        assertTrue(state.clusteredImages.values.all { it.left?.size == 1 })
    }

    @Test
    fun zoomOutMergesIndividualsIntoCluster() {
        var state = emptyState()
        // 5 separate cells at zoom 13 → 5 individuals.
        val images = (0 until 5).map { img(it, cellLat = it, cellLon = 0, zoom = 13) }
        state = receive(state, images, params = params(zoom = 13)).state

        // At zoom 10 (cell size ×8) the 5 fall into one cell and merge into a cluster.
        val r = receive(state, images, params = params(zoom = 10))
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size)
        assertEquals(
            5,
            r.toAdd.localClusters
                .first()
                .images.size,
        )
        // The 5 former individual annotations are replaced by the cluster.
        assertEquals(5, r.toRemove.images.size)
        assertEquals(1, state.clusteredImages.size)
        assertNotNull(
            state.clusteredImages.values
                .first()
                .right,
        )
        assertNull(
            state.clusteredImages.values
                .first()
                .left,
        )
    }

    @Test
    fun zoomOutMergesSurvivorsAndNewImagesIntoOneCluster() {
        var state = emptyState()
        val all = (0 until 6).map { img(it, cellLat = it, cellLon = 0, zoom = 13) }
        // First load shows only 2 of them, as individuals at zoom 13.
        state = receive(state, all.take(2), params = params(zoom = 13)).state

        // Zoom out: the 2 survivors plus 4 brand-new images share one cell → one cluster.
        val r = receive(state, all, params = params(zoom = 10))
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size) // exactly one cluster, no duplicates
        assertEquals(
            6,
            r.toAdd.localClusters
                .first()
                .images.size,
        )
        assertEquals(2, r.toRemove.images.size) // only the 2 survivors were on the map
        assertEquals(1, state.clusteredImages.size)
        assertEquals(
            6,
            state.clusteredImages.values
                .first()
                .right
                ?.images
                ?.size,
        )
    }

    @Test
    fun individualsStayPutOnZoomChangeWithoutChurn() {
        var state = emptyState()
        // Two far-apart individuals, each alone in its cell (below threshold).
        val images =
            listOf(
                img(0, cellLat = 0, cellLon = 0, zoom = 13),
                img(1, cellLat = 100, cellLon = 0, zoom = 13),
            )
        state = receive(state, images, params = params(zoom = 13)).state

        // Zoom change: still far apart and still individual → nothing should move.
        val r = receive(state, images, params = params(zoom = 12))
        state = r.state

        assertTrue(r.toAdd.isEmpty())
        assertTrue(r.toRemove.isEmpty())
        assertEquals(2, state.clusteredImages.size)
        assertTrue(state.clusteredImages.values.all { it.left?.size == 1 })
    }

    @Test
    fun subBucketZoomKeepsAnnotationsPut() {
        // Pinches that nudge the zoom but stay inside the same rounded-zoom bucket (17) must not
        // move the grid: the same images regroup into the same cells → no additions, no removals,
        // no growth in the cell map. (The bug this guards: the cell key includes a `size` derived
        // from the continuous camera span, whose last bits jitter every sub-bucket zoom, so every
        // cell looks new and the additive diff duplicates all annotations.)
        var state = emptyState()
        val images =
            cellImages(3, cellLat = 0, cellLon = 0, from = 0, zoom = 17) + // a cluster
                cellImages(1, cellLat = 9, cellLon = 9, from = 100, zoom = 17) // a lone individual
        state = receive(state, images, params = params(zoom = 17), cameraZoom = 17.0f).state
        val cellCount = state.clusteredImages.size

        // The server keeps returning the same set while the camera zooms within bucket 17.
        for (cameraZoom in listOf(17.09f, 17.23f, 17.41f, 17.49f)) {
            val r = receive(state, images, params = params(zoom = 17), cameraZoom = cameraZoom)
            state = r.state
            assertTrue("sub-bucket zoom $cameraZoom churned annotations", r.toAdd.isEmpty())
            assertTrue("sub-bucket zoom $cameraZoom removed annotations", r.toRemove.isEmpty())
            assertEquals(
                "sub-bucket zoom $cameraZoom grew the cell map",
                cellCount,
                state.clusteredImages.size,
            )
        }
    }

    // MARK: Server clusters (ModelCluster from the API)

    @Test
    fun serverClustersAddedOnFirstLoad() {
        var state = emptyState()
        val r =
            receive(
                state,
                emptyList(),
                clusters = listOf(serverCluster(1), serverCluster(2)),
                params = params(zoom = 10),
            )
        state = r.state

        assertEquals(2, r.toAdd.clusters.size)
        assertTrue(r.toRemove.isEmpty())
        assertEquals(2, state.clusters.size)
    }

    @Test
    fun newServerClustersAddedIncrementally() {
        var state = emptyState()
        val p = params(zoom = 10)
        val a = serverCluster(1)
        val b = serverCluster(2)
        state = receive(state, emptyList(), clusters = listOf(a), params = p).state

        val r = receive(state, emptyList(), clusters = listOf(a, b), params = p)
        state = r.state
        assertEquals(listOf(b), r.toAdd.clusters) // only the new one
        assertTrue(r.toRemove.isEmpty())
        assertEquals(setOf(a, b), state.clusters)
    }

    @Test
    fun serverClustersReplacedOnZoomChange() {
        var state = emptyState()
        val a = serverCluster(1)
        val b = serverCluster(2)
        val c = serverCluster(3)
        state =
            receive(state, emptyList(), clusters = listOf(a, b), params = params(zoom = 10)).state

        val r = receive(state, emptyList(), clusters = listOf(c), params = params(zoom = 11))
        state = r.state
        assertEquals(setOf(a, b), r.toRemove.clusters.toSet())
        assertEquals(listOf(c), r.toAdd.clusters)
        assertEquals(setOf(c), state.clusters)
    }

    @Test
    fun serverClustersUnchangedOnSameParamReload() {
        var state = emptyState()
        val p = params(zoom = 10)
        val a = serverCluster(1)
        state = receive(state, emptyList(), clusters = listOf(a), params = p).state

        val r = receive(state, emptyList(), clusters = listOf(a), params = p)
        assertTrue(r.toAdd.clusters.isEmpty())
        assertTrue(r.toRemove.clusters.isEmpty())
    }

    // MARK: Eviction (Android divergence: state stays bounded during long same-zoom pans)

    @Test
    fun evictionDropsOnlyAnnotationsOutsidePaddedRegion() {
        var state = emptyState()
        val p = params(zoom = 13)
        // Near: a cluster in cell (0,0) — coordinate ≈ (0.003°, 0.003°). Far: an individual in
        // cell (3000, 3000) — coordinate ≈ (16.5°, 16.5°), outside 3× a 2°-span region (±3°).
        val near = cellImages(2, cellLat = 0, cellLon = 0, from = 0, zoom = 13)
        val far = cellImages(1, cellLat = 3000, cellLon = 3000, from = 100, zoom = 13)
        val nearCluster = serverCluster(1) // coordinate (1°, 1°) — inside ±3°
        val farCluster = serverCluster(10) // coordinate (10°, 10°) — outside
        state =
            receive(state, near + far, clusters = listOf(nearCluster, farCluster), params = p).state

        val evicted =
            state
                .copy(region = Region(Coordinate.zero, Span(2.0, 2.0)))
                .evictingFarAnnotations()

        assertEquals(setOf(nearCluster), evicted.clusters)
        assertEquals(1, evicted.clusteredImages.size)
        assertEquals(
            2,
            evicted.clusteredImages.values
                .first()
                .right
                ?.images
                ?.size,
        )
    }

    // MARK: Edge cases

    @Test
    fun emptyInputProducesNoChanges() {
        var state = emptyState()
        val r = receive(state, emptyList(), params = params(zoom = 10))
        state = r.state
        assertTrue(r.toAdd.isEmpty())
        assertTrue(r.toRemove.isEmpty())
        assertTrue(state.clusteredImages.isEmpty())
        assertTrue(state.clusters.isEmpty())
    }

    @Test
    fun filterChangeClearsAndRebuilds() {
        var state = emptyState()
        val images = cellImages(5, zoom = 10)
        val first =
            receive(
                state,
                images,
                params = params(zoom = 10, filters = ImageRequestFilters.default),
            )
        state = first.state
        val oldCluster = first.toAdd.localClusters.first()

        // Same zoom, different filters → still triggers a clear.
        val painting = ImageRequestFilters(ImageRequestFilters.ImageKind.Painting)
        val r = receive(state, images, params = params(zoom = 10, filters = painting))
        state = r.state
        val newCluster = r.toAdd.localClusters.first()
        assertEquals(listOf(oldCluster.id), r.toRemove.localClusters.map { it.id })
        assertTrue(newCluster.id != oldCluster.id)
    }

    @Test
    fun firstLoadNeverClears() {
        val state = emptyState()
        // lastLoadedParams is null → no clearing, hence no spurious removals.
        val r = receive(state, cellImages(5, zoom = 13), params = params(zoom = 13))
        assertTrue(r.toRemove.isEmpty())
    }
}

// MARK: - Fixtures

/**
 * A visible longitude span for a (possibly fractional) camera zoom. Any function that halves per
 * zoom step works — the tests only assert *relative* grouping (e.g. zoom 13 vs 10 → ×8 cell); this
 * keeps the old numbers. Fractional input models a sub-bucket zoom (`360/2^17.4`).
 */
private fun spanForContinuous(zoom: Float): Double = 360.0 / 2.0.pow(zoom.toDouble())

private fun spanForZoom(zoom: Int): Double = spanForContinuous(zoom.toFloat())

/** Grid cell size in degrees for a zoom — mirrors `CLUSTERING_CELL_RATIO = 8`. */
private fun cellSize(zoom: Int): Double = spanForZoom(zoom) / 8.0

/**
 * An image at the *center* of grid cell `(lat, lon)` for `zoom`. Placing at the center guarantees
 * `floor(coord / size)` maps back to `(lat, lon)`.
 */
private fun img(
    cid: Int,
    cellLat: Int,
    cellLon: Int,
    zoom: Int,
): ModelImage {
    val s = cellSize(zoom)
    return mockImage(
        cid = cid,
        coordinate = Coordinate(latitude = cellLat * s + s / 2, longitude = cellLon * s + s / 2),
    )
}

/**
 * `n` images sharing one cell `(lat, lon)` (identical coordinate → same cell at any zoom), with
 * cids `start until start + n`.
 */
private fun cellImages(
    n: Int,
    cellLat: Int = 0,
    cellLon: Int = 0,
    from: Int = 0,
    zoom: Int,
): List<ModelImage> = (0 until n).map { img(from + it, cellLat, cellLon, zoom) }

/** A distinct server-side cluster per `id` (differs in preview cid, coordinate and count). */
private fun serverCluster(id: Int): ModelCluster =
    ModelCluster(
        preview = mockImage(cid = id, coordinate = Coordinate.zero),
        coordinate = Coordinate(latitude = id.toDouble(), longitude = id.toDouble()),
        count = id,
    )

private fun mockImage(
    cid: Int,
    coordinate: Coordinate,
): ModelImage =
    ModelImage(
        cid = cid,
        imagePath = "",
        title = "",
        dir = null,
        coordinate = coordinate,
        date = ImageDate(year = 1900, year2 = 1900),
    )

private fun params(
    zoom: Int,
    filters: ImageRequestFilters = ImageRequestFilters.default,
): AnnotationLoadingParams =
    AnnotationLoadingParams(
        zoom = zoom,
        coordinates = emptyList(),
        startAt = 0.0,
        filters = filters,
    )

private fun emptyState(): MapState =
    MapState(
        region =
            Region(
                center = Coordinate.zero,
                span = Span(latitudeDelta = 0.0, longitudeDelta = 0.0),
            ),
        zoom = 13,
        cameraZoom = 13f,
        filters = ImageRequestFilters.default,
        isLoading = false,
        lastLoadedParams = null,
        clusters = emptySet(),
        clusteredImages = emptyMap(),
    )

private data class Received(
    val state: MapState,
    val toAdd: List<AnnotationValue>,
    val toRemove: List<AnnotationValue>,
)

/**
 * Runs the diff and then advances `lastLoadedParams`, mimicking MapModel `.loaded` so consecutive
 * `receive` calls behave like consecutive server loads. Kotlin has no `inout`, so the caller
 * threads the returned [Received.state] into the next call.
 *
 * [cameraZoom] is the raw (possibly fractional) camera zoom; the visible span follows from it. By
 * default it's the integer [AnnotationLoadingParams.zoom], so the clustering cell is exactly
 * `spanForZoom(zoom)/8` as the fixtures expect. A test may pass a sub-bucket value (e.g. 17.4 while
 * `params.zoom == 17`) to check the grid stays put within a rounded-zoom bucket.
 */
private fun receive(
    state: MapState,
    images: List<ModelImage>,
    clusters: List<ModelCluster> = emptyList(),
    params: AnnotationLoadingParams,
    cameraZoom: Float = params.zoom.toFloat(),
): Received {
    // The reducer sets region (hence the clustering span) + cameraZoom on regionChanged before the
    // load; mirror that so the load's cell size is derived the same way.
    val stateForZoom =
        state.copy(
            region = state.region.copy(span = Span(0.0, spanForContinuous(cameraZoom))),
            cameraZoom = cameraZoom,
        )
    val diff = makeDiffAfterReceived(images, clusters, params, stateForZoom)
    return Received(
        state = diff.state.copy(lastLoadedParams = params),
        toAdd = diff.toAdd,
        toRemove = diff.toRemove,
    )
}

private val List<AnnotationValue>.images: List<ModelImage> get() = mapNotNull { it.image }
private val List<AnnotationValue>.clusters: List<ModelCluster> get() = mapNotNull { it.cluster }
private val List<AnnotationValue>.localClusters: List<ModelLocalCluster>
    get() = mapNotNull { it.localCluster }
