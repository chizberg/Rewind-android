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
 * The algorithm now derives the cell from the visible longitude span (`region.span / 8`), so
 * [receive] sets `region.span` per the load's zoom (as the reducer's `regionChanged` does), and the
 * fixture places images at cell centers using the same [cellSize]. Grouping is identical to before;
 * `spanForZoom` keeps the old per-zoom scale so the relative assertions (e.g. zoom 13 vs 10 → ×8
 * cell) are unchanged.
 */
class LocalClusteringTest {
    // MARK: Cluster formation (fresh state, first load → no clearing)

    @Test
    fun fourImagesStayIndividual() {
        var state = emptyState()
        val r = receive(state, cellImages(4, zoom = 13), params = params(zoom = 13))
        state = r.state

        assertEquals(4, r.toAdd.images.size)
        assertTrue(r.toAdd.localClusters.isEmpty())
        assertTrue(r.toRemove.isEmpty())
        assertEquals(1, state.clusteredImages.size)
        assertEquals(
            4,
            state.clusteredImages.values
                .first()
                .left
                ?.size,
        )
    }

    @Test
    fun fiveImagesFormClusterAtThreshold() {
        var state = emptyState()
        val r = receive(state, cellImages(5, zoom = 13), params = params(zoom = 13))
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size)
        assertEquals(
            5,
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
        // 6 images in cell (0,0) → cluster; 3 in cell (5,5) → individuals.
        val images =
            cellImages(6, cellLat = 0, cellLon = 0, from = 0, zoom = 13) +
                cellImages(3, cellLat = 5, cellLon = 5, from = 100, zoom = 13)
        val r = receive(state, images, params = params(zoom = 13))
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size)
        assertEquals(
            6,
            r.toAdd.localClusters
                .first()
                .images.size,
        )
        assertEquals(3, r.toAdd.images.size)
        assertEquals(2, state.clusteredImages.size)
        assertTrue(state.clusteredImages.values.any { it.right != null })
        assertTrue(state.clusteredImages.values.any { it.left?.size == 3 })
    }

    // MARK: Incremental growth (same zoom & filters → additive path)

    @Test
    fun twoPlusThreePromotesToCluster() {
        var state = emptyState()
        val p = params(zoom = 13)
        val base = cellImages(2, from = 0, zoom = 13)
        state = receive(state, base, params = p).state // 2 individuals

        // Next load returns the original 2 plus 3 new ones (5 total in the cell).
        val grown = base + cellImages(3, from = 100, zoom = 13)
        val r = receive(state, grown, params = p)
        state = r.state

        assertEquals(1, r.toAdd.localClusters.size)
        assertEquals(
            5,
            r.toAdd.localClusters
                .first()
                .images.size,
        )
        assertTrue(r.toAdd.images.isEmpty())
        // The 2 previously-individual annotations are removed in favor of the cluster.
        assertEquals(2, r.toRemove.images.size)
        assertNotNull(
            state.clusteredImages.values
                .first()
                .right,
        )
    }

    @Test
    fun twoPlusTwoStaysIndividual() {
        var state = emptyState()
        val p = params(zoom = 13)
        val base = cellImages(2, from = 0, zoom = 13)
        state = receive(state, base, params = p).state

        val grown = base + cellImages(2, from = 100, zoom = 13) // 4 < threshold
        val r = receive(state, grown, params = p)
        state = r.state

        assertEquals(2, r.toAdd.images.size)
        assertTrue(r.toAdd.localClusters.isEmpty())
        assertTrue(r.toRemove.isEmpty())
        assertEquals(
            4,
            state.clusteredImages.values
                .first()
                .left
                ?.size,
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
        val images = cellImages(3, zoom = 13)
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
 * A visible longitude span for a zoom. Any function that halves per zoom step works — the tests
 * only assert *relative* grouping (e.g. zoom 13 vs 10 → ×8 cell); this keeps the old numbers.
 */
private fun spanForZoom(zoom: Int): Double = 360.0 / 2.0.pow(zoom)

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
 */
private fun receive(
    state: MapState,
    images: List<ModelImage>,
    clusters: List<ModelCluster> = emptyList(),
    params: AnnotationLoadingParams,
): Received {
    // The reducer sets region (hence the clustering span) on regionChanged before the load; mirror
    // that so the load's cell size matches params.zoom.
    val stateForZoom =
        state.copy(
            region = state.region.copy(span = Span(0.0, spanForZoom(params.zoom))),
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
