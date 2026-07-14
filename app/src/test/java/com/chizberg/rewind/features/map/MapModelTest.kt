@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.ImageDate
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.domain.delta
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
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
                model(MapAction.External.Map.RegionChanged(region(latOffset = i * 0.01), zoom = 13))
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

            model(MapAction.External.Map.RegionChanged(region(latOffset = 0.0), zoom = 13))
            advanceTimeBy(150.milliseconds)
            runCurrent()
            assertEquals(1, remote.loadCount) // first load started, hanging

            model(MapAction.External.Map.RegionChanged(region(latOffset = 5.0), zoom = 13))
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

            // 12 images in distinct cells (each below the local-cluster threshold) + a server cluster.
            val imagesA = (0 until 12).map { img(it + 1, cellLat = it, cellLon = 0, zoom = 10) }
            val clusterA = serverCluster(41)
            remote.response = imagesA to listOf(clusterA)
            model(MapAction.External.Map.RegionChanged(region(), zoom = 10))
            advanceUntilIdle()
            assertEquals(imagesA.toSet(), model.state.value.imageValues)
            assertEquals(listOf(clusterA), model.state.value.clusterValues)
            assertEquals(
                10,
                model.state.value.lastLoadedParams
                    ?.zoom,
            )

            // Zoom in; while the response is gated, annotations stay intact.
            val imagesB = (0 until 3).map { img(it + 100, cellLat = it, cellLon = 0, zoom = 12) }
            val clusterB = serverCluster(42)
            remote.gateNextCall = true
            remote.response = imagesB to listOf(clusterB)
            model(MapAction.External.Map.RegionChanged(region(), zoom = 12))
            advanceUntilIdle()
            assertEquals(2, remote.loadCount)
            assertTrue(model.state.value.isLoading)
            assertEquals(imagesA.toSet(), model.state.value.imageValues) // old kept while loading

            // The response lands: a zoom change replaces annotations wholesale.
            remote.openGate()
            advanceUntilIdle()
            assertEquals(imagesB.toSet(), model.state.value.imageValues)
            assertEquals(listOf(clusterB), model.state.value.clusterValues)

            // Pan at the same zoom: nothing is removed, new images append.
            val imagesC =
                (0 until 2).map {
                    img(
                        it + 200,
                        cellLat = it + 10,
                        cellLon = 5,
                        zoom = 12,
                    )
                }
            remote.response = imagesC to emptyList()
            model(MapAction.External.Map.RegionChanged(region(latOffset = 1.0), zoom = 12))
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
            model(MapAction.External.Map.RegionChanged(region(), zoom = 10))
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

    // MARK: - Deferred mirrors of iOS MapModelTests
    // These exercise features not yet ported to the M6 reducer; each lands (un-ignored, fleshed
    // out) with its feature's milestone. Kept as named stubs so the parity gap with iOS is visible.

    @Ignore("LocationModel not ported yet — lands with the location button")
    @Test
    fun firstLocationRecentersMapOnce() {
        TODO("mirror iOS once LocationModel + newLocationState land")
    }

    @Ignore("map controls minimization not ported yet")
    @Test
    fun dragMinimizesControlsAndAutoUnfoldsUnlessUserMinimized() {
        TODO("mirror iOS once the controls minimization/unfold land")
    }

    @Ignore("annotation selection routing not ported yet")
    @Test
    fun annotationSelectionRoutesByType() {
        TODO("mirror iOS once annotationSelected + image details/list land")
    }

    @Ignore("focusOn action not ported yet")
    @Test
    fun focusOnRecentersAndReloads() {
        TODO("mirror iOS once the focusOn action lands")
    }
}

// MARK: - Fixtures

private fun region(latOffset: Double = 0.0): Region =
    Region(
        center = Coordinate(latitude = 50.0 + latOffset, longitude = 14.0),
        span = Span(latitudeDelta = 0.5, longitudeDelta = 0.5),
    )

/** Grid cell size in degrees for a zoom — mirrors `CLUSTERING_CELL_RATIO = 8`. */
private fun cellSize(zoom: Int): Double = delta(zoom) / 8.0

/**
 * An image at the *center* of grid cell `(lat, lon)` for `zoom` (mirrors LocalClusteringTest), so
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
