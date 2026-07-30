@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.features.map

import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageDate
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.network.AnnotationLoadingParams
import com.chizberg.rewind.network.Remote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.floor
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Mirror of iOS `MapModelTests` for the Android (pure, declarative) reducer. Each test walks a
 * realistic multi-step flow (region changes, loads landing or hanging, filter changes) and checks
 * the resulting state after every step. The four scenarios portable to M6 are covered here; the
 * rest are @Ignore stubs (see the bottom) that land with their feature's milestone.
 *
 * Determinism via virtual time; the reducer runs on a scope over the test scheduler. Where iOS
 * asserts on the map's `visibleAnnotations`, we assert on `state.annotations` — the declarative
 * render source of truth — via the [imageValues]/[clusterValues] projections.
 */
class MapModelTest {
    /** A burst of region changes inside the debounce window triggers exactly one load. */
    @Test
    fun regionChangeBurstCollapsesToOneLoad() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(remote.asRemote, onLoadFailed = {}, scope = scope, now = { 0.0 })

            repeat(5) { i ->
                model(
                    MapAction.External.Map.RegionChanged(
                        region(latOffset = i * 0.01),
                        zoom = 13,
                        cameraZoom = 13f,
                    ),
                )
            }
            advanceUntilIdle()

            assertEquals(1, remote.loadCount)
        }

    /**
     * A second region change cancels the in-flight load (shared id `load_annotations`); only the
     * second result lands, and the cancelled first raises no alert.
     */
    @Test
    fun secondRegionChangeCancelsFirstLoad() =
        reducerTest { scope ->
            val alerts = mutableListOf<Throwable>()
            val remote = FakeAnnotationsRemote(hangFirstCall = true)
            val model =
                makeMapModel(
                    remote.asRemote,
                    onLoadFailed = { alerts += it },
                    scope = scope,
                    now = { 0.0 },
                )

            model(
                MapAction.External.Map.RegionChanged(
                    region(latOffset = 0.0),
                    zoom = 13,
                    cameraZoom = 13f,
                ),
            )
            advanceTimeBy(150.milliseconds)
            runCurrent()
            assertEquals(1, remote.loadCount) // first load started, hanging

            model(
                MapAction.External.Map.RegionChanged(
                    region(latOffset = 5.0),
                    zoom = 13,
                    cameraZoom = 13f,
                ),
            )
            advanceUntilIdle()
            assertEquals(2, remote.loadCount) // second load cancelled the first

            // The landed params are the second load's, never the (cancelled) first's.
            val landed = model.state.value.lastLoadedParams
            assertNotNull(landed)
            assertEquals(remote.receivedParams[1].coordinates, landed?.coordinates)
            assertNotEquals(remote.receivedParams[0].coordinates, landed?.coordinates)
            assertTrue(alerts.isEmpty()) // cancellation is silent
        }

    /**
     * Loads at zoom 10; zooming in keeps the old annotations until the (gated) response arrives,
     * then replaces them wholesale; panning at the same zoom removes nothing and appends the new
     * images (the M6 core behavior — a same-zoom pan accumulates instead of churning).
     */
    @Test
    fun zoomChangeReplacesAnnotationsPanKeepsThem() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(remote.asRemote, onLoadFailed = {}, scope = scope, now = { 0.0 })

            // 4 images in distinct cells around the region center (each below the local-cluster
            // threshold) + a server cluster. Geography is coherent with the regions below: every
            // annotation asserted to survive a region change must sit inside that region's
            // eviction keep-window (×3 span) — the reducer now evicts far-off state.
            val imagesA =
                listOf(0 to 0, 0 to -1, -1 to 0, -1 to -1).mapIndexed { i, (dLat, dLon) ->
                    img(
                        i + 1,
                        cellLat = centerCellLat(10) + dLat,
                        cellLon = centerCellLon(10) + dLon,
                        zoom = 10,
                    )
                }
            val clusterA = serverCluster(41)
            remote.response = imagesA to listOf(clusterA)
            model(
                MapAction.External.Map.RegionChanged(
                    region(zoom = 10),
                    zoom = 10,
                    cameraZoom = 10f,
                ),
            )
            advanceUntilIdle()
            assertEquals(imagesA.toSet(), model.state.value.imageValues)
            assertEquals(listOf(clusterA), model.state.value.clusterValues)
            assertEquals(
                10,
                model.state.value.lastLoadedParams
                    ?.zoom,
            )

            // Zoom in; while the response is gated, annotations stay intact.
            val imagesB =
                (0 until 3).map {
                    img(
                        it + 100,
                        cellLat = centerCellLat(12) + it,
                        cellLon = centerCellLon(12),
                        zoom = 12,
                    )
                }
            val clusterB = serverCluster(42)
            remote.gateNextCall = true
            remote.response = imagesB to listOf(clusterB)
            model(
                MapAction.External.Map.RegionChanged(
                    region(zoom = 12),
                    zoom = 12,
                    cameraZoom = 12f,
                ),
            )
            advanceUntilIdle()
            assertEquals(2, remote.loadCount)
            assertTrue(model.state.value.isLoading)
            assertEquals(imagesA.toSet(), model.state.value.imageValues) // old kept while loading

            // The response lands: a zoom change replaces annotations wholesale.
            remote.openGate()
            advanceUntilIdle()
            assertEquals(imagesB.toSet(), model.state.value.imageValues)
            assertEquals(listOf(clusterB), model.state.value.clusterValues)

            // Pan half a viewport at the same zoom: nothing visible is removed, new images append.
            val imagesC =
                (0 until 2).map {
                    img(
                        it + 200,
                        cellLat = centerCellLat(12) + 4 + it,
                        cellLon = centerCellLon(12) + 2,
                        zoom = 12,
                    )
                }
            remote.response = imagesC to emptyList()
            model(
                MapAction.External.Map.RegionChanged(
                    region(zoom = 12, latOffset = spanForZoom(12) / 2),
                    zoom = 12,
                    cameraZoom = 12f,
                ),
            )
            advanceUntilIdle()
            assertEquals((imagesB + imagesC).toSet(), model.state.value.imageValues)
            assertEquals(listOf(clusterB), model.state.value.clusterValues) // server cluster kept
            assertEquals(
                12,
                model.state.value.lastLoadedParams
                    ?.zoom,
            )
        }

    /**
     * A filter change resets a narrowed year range immediately, clears the annotations before the
     * reload response arrives, and the new load lands with the new filters applied.
     */
    @Test
    fun filterChangeResetsYearRangeClearsAndReloads() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(remote.asRemote, onLoadFailed = {}, scope = scope, now = { 0.0 })

            // cid 1 passes both the old and the new filters — it must survive the clear + reload.
            val survivor = img(1, cellLat = 1, cellLon = 0, zoom = 10)
            remote.response =
                (listOf(survivor) + (2..3).map { img(it, cellLat = it, cellLon = 0, zoom = 10) }) to
                listOf(serverCluster(41))
            model(
                MapAction.External.Map.RegionChanged(
                    region(zoom = 10),
                    zoom = 10,
                    cameraZoom = 10f,
                ),
            )
            advanceUntilIdle()
            assertTrue(
                model.state.value.annotations
                    .isNotEmpty(),
            )

            // Switching imageKind discards the narrowed year range immediately.
            val painting =
                ImageRequestFilters(
                    yearRange = 1900..1950,
                    imageKind = ImageRequestFilters.ImageKind.Painting,
                )
            val newImages = listOf(survivor, img(10, cellLat = 0, cellLon = 0, zoom = 10))
            remote.gateNextCall = true
            remote.response = newImages to emptyList()
            model(MapAction.External.Ui.FiltersChanged(painting))
            assertEquals(
                ImageRequestFilters.ImageKind.Painting,
                model.state.value.filters.imageKind,
            )
            assertEquals(
                ImageRequestFilters.ImageKind.Painting.maxRange,
                model.state.value.filters.yearRange,
            )

            // The debounced follow-up clears the annotations before the reload response arrives.
            advanceUntilIdle()
            assertEquals(2, remote.loadCount)
            assertTrue(
                model.state.value.annotations
                    .isEmpty(),
            )
            assertTrue(model.state.value.isLoading)

            // The reload lands with the new filters applied.
            remote.openGate()
            advanceUntilIdle()
            assertEquals(newImages.toSet(), model.state.value.imageValues)
            assertTrue(
                model.state.value.clusterValues
                    .isEmpty(),
            )
            assertEquals(
                ImageRequestFilters.ImageKind.Painting,
                model.state.value.lastLoadedParams
                    ?.filters
                    ?.imageKind,
            )
        }

    // MARK: - Previews (M8)

    /**
     * Under the limit, the strip lists one card per visible image with no "view as list" tail, and
     * a server cluster contributes its preview image (flatten across annotation kinds).
     */
    @Test
    fun previewsIncludeClusterPreviewUnderLimit() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(
                    remote.asRemote,
                    onLoadFailed = {},
                    scope = scope,
                    now = { 0.0 },
                    sorting = { ImageSorting.DateDescending },
                )

            remote.response = imagesNearCenter(3, zoom = 12) to listOf(serverCluster(41))
            model.loadRegion(12)
            advanceUntilIdle()

            // 3 loose images + the cluster's preview (cid 41), all deduped by cid.
            assertEquals(
                setOf(1, 2, 3, 41),
                model.state.value.currentRegionImages
                    .map { it.cid }
                    .toSet(),
            )
            assertEquals(
                4,
                model.state.value.previews
                    .count { it is PreviewCard.Image },
            )
            assertFalse(
                model.state.value.previews
                    .any { it is PreviewCard.ViewAsList },
            )
        }

    /** Above the limit, the strip shows the first [PREVIEW_LIMIT] cards plus a "view as list" tail. */
    @Test
    fun previewsCapAtLimitWithViewAsListTail() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(
                    remote.asRemote,
                    onLoadFailed = {},
                    scope = scope,
                    now = { 0.0 },
                    sorting = { ImageSorting.DateDescending },
                )

            remote.response = imagesNearCenter(11, zoom = 12) to emptyList()
            model.loadRegion(12)
            advanceUntilIdle()

            val previews = model.state.value.previews
            assertEquals(PREVIEW_LIMIT + 1, previews.size)
            assertEquals(PREVIEW_LIMIT, previews.count { it is PreviewCard.Image })
            assertEquals(PreviewCard.ViewAsList, previews.last())
            assertEquals(11, model.state.value.currentRegionImages.size)
        }

    /**
     * A server cluster whose preview shares a cid with a loose image adds no second card: region
     * images and the strip dedupe by cid across annotation kinds.
     */
    @Test
    fun previewsDedupeClusterPreviewSharingCid() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(
                    remote.asRemote,
                    onLoadFailed = {},
                    scope = scope,
                    now = { 0.0 },
                    sorting = { ImageSorting.DateDescending },
                )

            // 3 loose images (cids 1..3); the cluster preview duplicates cid 2.
            remote.response = imagesNearCenter(3, zoom = 12) to listOf(serverCluster(2))
            model.loadRegion(12)
            advanceUntilIdle()

            assertEquals(
                setOf(1, 2, 3),
                model.state.value.currentRegionImages
                    .map { it.cid }
                    .toSet(),
            )
            assertEquals(3, model.state.value.previews.size)
        }

    /** An empty visible region shows the single "no images" card, not an empty strip. */
    @Test
    fun emptyRegionShowsNoImagesCard() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(remote.asRemote, onLoadFailed = {}, scope = scope, now = { 0.0 })

            remote.response = emptyList<ModelImage>() to emptyList()
            model.loadRegion(12)
            advanceUntilIdle()

            assertEquals(listOf(PreviewCard.NoImages), model.state.value.previews)
            assertTrue(
                model.state.value.currentRegionImages
                    .isEmpty(),
            )
        }

    /**
     * While a reload is in flight the strip must not recompute — even though the filter change
     * cleared the annotations, `updatePreviews` is gated by `isLoading`, so the old strip survives
     * until the load lands (without the gate the cleared region would flash "no images").
     */
    @Test
    fun previewsGatedWhileLoadingKeepOldStrip() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val model =
                makeMapModel(
                    remote.asRemote,
                    onLoadFailed = {},
                    scope = scope,
                    now = { 0.0 },
                    sorting = { ImageSorting.DateDescending },
                )

            remote.response = imagesNearCenter(3, zoom = 12) to emptyList()
            model.loadRegion(12)
            advanceUntilIdle()
            assertEquals(3, model.state.value.previews.size)

            // A filter change clears the annotations and starts a gated reload.
            remote.gateNextCall = true
            remote.response = imagesNearCenter(2, zoom = 12, startCid = 100) to emptyList()
            model(
                MapAction.External.Ui.FiltersChanged(
                    ImageRequestFilters(imageKind = ImageRequestFilters.ImageKind.Painting),
                ),
            )
            advanceUntilIdle()
            assertTrue(model.state.value.isLoading)
            assertEquals(3, model.state.value.previews.size) // old strip kept despite cleared state

            remote.openGate()
            advanceUntilIdle()
            assertEquals(2, model.state.value.previews.size) // recomputed once the load lands
        }

    /** The injected sorting orders the strip: descending puts the newest first, ascending the oldest. */
    @Test
    fun previewOrderFollowsInjectedSorting() =
        reducerTest { scope ->
            val descRemote = FakeAnnotationsRemote()
            val descModel =
                makeMapModel(
                    descRemote.asRemote,
                    onLoadFailed = {},
                    scope = scope,
                    now = { 0.0 },
                    sorting = { ImageSorting.DateDescending },
                )
            descRemote.response = imagesNearCenter(3, zoom = 12) to emptyList()
            descModel.loadRegion(12)
            advanceUntilIdle()
            // cids 1..3 carry years 1900..1902, so the newest (cid 3) leads under descending order.
            assertEquals(
                3,
                descModel.state.value.previews
                    .first()
                    .image
                    ?.cid,
            )

            val ascRemote = FakeAnnotationsRemote()
            val ascModel =
                makeMapModel(
                    ascRemote.asRemote,
                    onLoadFailed = {},
                    scope = scope,
                    now = { 0.0 },
                    sorting = { ImageSorting.DateAscending },
                )
            ascRemote.response = imagesNearCenter(3, zoom = 12) to emptyList()
            ascModel.loadRegion(12)
            advanceUntilIdle()
            assertEquals(
                1,
                ascModel.state.value.previews
                    .first()
                    .image
                    ?.cid,
            )
        }

    // MARK: - Deferred mirrors of iOS MapModelTests
    // These exercise features not yet ported to the M6 reducer; each lands (un-ignored, fleshed
    // out) with its feature's milestone. Kept as named stubs so the parity gap with iOS is visible.

    /**
     * The first location fix recenters the map exactly once (zoom 15, not animated, via the
     * injected [CameraFocus] channel — the reducer doesn't own the camera, see `CameraFocus.kt`);
     * the map
     * then reacts with a region change that loads normally; a second fix only updates state without
     * recentering again; and a nil-location fix does not erase the last known location. Mirrors iOS
     * `MapModelTests.firstLocationRecentersMapOnce` (`MapModelTests.swift:140-174`).
     */
    @Test
    fun firstLocationRecentersMapOnce() =
        reducerTest { scope ->
            val remote = FakeAnnotationsRemote()
            val focuses = mutableListOf<CameraFocus>()
            val model =
                makeMapModel(
                    remote.asRemote,
                    onLoadFailed = {},
                    scope = scope,
                    now = { 0.0 },
                    focusCamera = { focuses += it },
                )

            val first = Coordinate(latitude = 55.75, longitude = 37.61)
            model(
                MapAction.External.NewLocationState(
                    LocationState(location = first, isAccessGranted = true),
                ),
            )
            assertEquals(listOf(CameraFocus(first, 15f, animated = false)), focuses)
            assertEquals(first, model.state.value.locationState.location)

            // The map reacts with a region change → annotations load for the new region as normal.
            remote.response = listOf(img(1, cellLat = 0, cellLon = 0, zoom = 15)) to emptyList()
            model(
                MapAction.External.Map.RegionChanged(
                    region(zoom = 15),
                    zoom = 15,
                    cameraZoom = 15f,
                ),
            )
            advanceUntilIdle()
            assertEquals(
                15,
                model.state.value.lastLoadedParams
                    ?.zoom,
            )

            // A second fix updates state but leaves the map alone: no further recenter.
            val second = Coordinate(latitude = 59.94, longitude = 30.31)
            model(
                MapAction.External.NewLocationState(
                    LocationState(location = second, isAccessGranted = true),
                ),
            )
            assertEquals(1, focuses.size)
            assertEquals(second, model.state.value.locationState.location)

            // A nil-location update (e.g. a transient provider hiccup) keeps the last known location.
            model(
                MapAction.External.NewLocationState(
                    LocationState(location = null, isAccessGranted = true),
                ),
            )
            assertEquals(1, focuses.size)
            assertEquals(second, model.state.value.locationState.location)
        }

    @Ignore("map controls minimization not ported yet")
    @Test
    fun dragMinimizesControlsAndAutoUnfoldsUnlessUserMinimized() {
        TODO("mirror iOS once the controls minimization/unfold land")
    }

    @Ignore("focusOn action not ported yet")
    @Test
    fun focusOnRecentersAndReloads() {
        TODO("mirror iOS once the focusOn action lands")
    }
}

// MARK: - Fixtures

/**
 * A region whose longitude span matches [zoom] — the reducer derives the clustering cell from
 * `region.span.longitudeDelta / 8`, so the span must scale with zoom for the [img] placement (which
 * uses the same [cellSize]) to line up. Mirrors LocalClusteringTest.
 */
private fun region(
    zoom: Int = 13,
    latOffset: Double = 0.0,
): Region =
    Region(
        center = Coordinate(latitude = 50.0 + latOffset, longitude = 14.0),
        span = Span(latitudeDelta = spanForZoom(zoom), longitudeDelta = spanForZoom(zoom)),
    )

/** A visible longitude span for a zoom (halves per zoom step); mirrors LocalClusteringTest. */
private fun spanForZoom(zoom: Int): Double = 360.0 / 2.0.pow(zoom)

/** Dispatches a region change at [zoom] (the previews tests all operate at a single zoom). */
private fun Reducer<MapState, MapAction>.loadRegion(
    zoom: Int,
    latOffset: Double = 0.0,
) = this(
    MapAction.External.Map.RegionChanged(
        region(zoom = zoom, latOffset = latOffset),
        zoom = zoom,
        cameraZoom = zoom.toFloat(),
    ),
)

/** Grid cell size in degrees for a zoom — mirrors `CLUSTERING_CELL_RATIO = 8`. */
private fun cellSize(zoom: Int): Double = spanForZoom(zoom) / 8.0

/** Index of the grid cell containing the [region]-center latitude (50°) at [zoom]. */
private fun centerCellLat(zoom: Int): Int = floor(50.0 / cellSize(zoom)).toInt()

/** Index of the grid cell containing the [region]-center longitude (14°) at [zoom]. */
private fun centerCellLon(zoom: Int): Int = floor(14.0 / cellSize(zoom)).toInt()

/**
 * An image at the *center* of grid cell `(lat, lon)` for `zoom` (mirrors LocalClusteringTest), so
 * `floor(coord / size)` maps back to `(lat, lon)`. Distinct [year]s make date sorting deterministic.
 */
private fun img(
    cid: Int,
    cellLat: Int,
    cellLon: Int,
    zoom: Int,
    year: Int = 1900,
): ModelImage {
    val s = cellSize(zoom)
    return mockImage(
        cid = cid,
        coordinate = Coordinate(latitude = cellLat * s + s / 2, longitude = cellLon * s + s / 2),
        year = year,
    )
}

/**
 * [count] loose images in distinct cells within ±2 of the region center at [zoom] — each cell holds
 * one image (below the local-cluster threshold) and every cell sits inside the visible region, so
 * all of them show up in the preview strip. Years increase with the index, so the newest is last.
 */
private fun imagesNearCenter(
    count: Int,
    zoom: Int,
    startCid: Int = 1,
): List<ModelImage> {
    val offsets = (-1..1).flatMap { dLat -> (-1..2).map { dLon -> dLat to dLon } }
    return (0 until count).map { i ->
        val (dLat, dLon) = offsets[i]
        img(
            cid = startCid + i,
            cellLat = centerCellLat(zoom) + dLat,
            cellLon = centerCellLon(zoom) + dLon,
            zoom = zoom,
            year = 1900 + i,
        )
    }
}

/**
 * A distinct server-side cluster per `id` (differs in preview cid, coordinate and count), placed
 * at the fixtures' region center so it survives eviction on the region changes it must outlive.
 */
private fun serverCluster(id: Int): ModelCluster =
    ModelCluster(
        preview = mockImage(cid = id, coordinate = Coordinate.zero),
        coordinate = Coordinate(latitude = 50.0 + id * 1e-4, longitude = 14.0),
        count = id,
    )

private fun mockImage(
    cid: Int,
    coordinate: Coordinate,
    year: Int = 1900,
): ModelImage =
    ModelImage(
        cid = cid,
        imagePath = "",
        title = "",
        dir = null,
        coordinate = coordinate,
        date = ImageDate(year = year, year2 = year),
    )

/** The loose images currently on the map (from free clustering cells), keyed by cid. */
private val MapState.imageValues: Set<ModelImage>
    get() = annotations.mapNotNull { it.image }.toSet()

/** The server clusters currently on the map. */
private val MapState.clusterValues: List<ModelCluster>
    get() = annotations.mapNotNull { it.cluster }

/**
 * A controllable annotations remote: counts loads, records params, serves a mutable [response],
 * and can suspend a load — either [hangFirstCall] (first call hangs until cancelled, so a second
 * load can cancel it) or [gateNextCall] (the next call suspends until [openGate], so a load can be
 * held in-flight while state is inspected).
 */
private class FakeAnnotationsRemote(
    private val hangFirstCall: Boolean = false,
) {
    var loadCount = 0
        private set
    val receivedParams = mutableListOf<AnnotationLoadingParams>()
    var response: Pair<List<ModelImage>, List<ModelCluster>> =
        emptyList<ModelImage>() to emptyList()

    /** When set, the *next* load suspends until [openGate]; cleared once consumed. */
    var gateNextCall = false
    private var gate: CompletableDeferred<Unit>? = null

    val asRemote: AnnotationsRemote =
        Remote { params ->
            loadCount += 1
            receivedParams += params
            val gated = gateNextCall
            gateNextCall = false
            if (loadCount == 1 && hangFirstCall) {
                delay(Duration.INFINITE) // cancelled when the next load replaces this one
            }
            if (gated) {
                val g = CompletableDeferred<Unit>()
                gate = g
                g.await()
            }
            response
        }

    /** Releases a load suspended by [gateNextCall]. */
    fun openGate() {
        gate?.complete(Unit)
        gate = null
    }
}

/**
 * Runs a test giving the reducer a scope on the test scheduler (NOT `backgroundScope`, whose tasks
 * `advanceUntilIdle()` ignores). The scope is cancelled afterwards so pending effects don't leak
 * into `runTest`'s completion check. Mirrors the helper in ReducerTest.
 */
private fun reducerTest(body: suspend TestScope.(scope: CoroutineScope) -> Unit) =
    runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
    }
