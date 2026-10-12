package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.image.RGBPlanes
import com.chizberg.rewind.colorization.image.bicubicResized
import com.chizberg.rewind.colorization.image.padded
import com.chizberg.rewind.colorization.image.resized
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Mirrors iOS `LabTests`. */
class LabTest {
    @Test
    fun lightnessFollowsTheOpenCVConvention() {
        val values = floatArrayOf(0f, 0.01f, 0.5f, 1f)
        val grays = RGBPlanes(PlaneSize(4, 1), values, values, values)

        val lightness = Lab.lightness(grays).values

        assertNear(0f, lightness[0], 0.01f)
        assertNear(0.699f, lightness[1], 0.01f)
        assertNear(53.387f, lightness[2], 0.01f)
        assertNear(100f, lightness[3], 0.01f)
    }

    @Test
    fun lightnessComesFromLuminanceNotFromTheAverage() {
        val pixels =
            RGBPlanes(
                PlaneSize(2, 1),
                floatArrayOf(1f, 0f),
                floatArrayOf(0f, 1f),
                floatArrayOf(0f, 0f),
            )

        val lightness = Lab.lightness(pixels).values

        assertNear(53.241f, lightness[0], 0.01f)
        assertNear(87.735f, lightness[1], 0.01f)
    }

    @Test
    fun labBackToRGBFollowsOpenCVOnBothSegmentsAndClampsOutOfGamutChannels() {
        val size = PlaneSize(6, 1)
        val lightness = FloatPlane(size, floatArrayOf(50f, 50f, 80f, 20f, 100f, 6f))
        val ab =
            ABPlanes(
                size,
                floatArrayOf(0f, 40f, -30f, 10f, 0f, 3f),
                floatArrayOf(0f, 20f, 60f, -40f, 0f, 8f),
            )

        val rgb = Lab.rgb(lightness, ab)

        val expected =
            floatArrayOf(
                0.4663f,
                0.7371f,
                0.6957f,
                0f,
                1f,
                0.1109f,
                0.4663f,
                0.3465f,
                0.8285f,
                0.1859f,
                1f,
                0.0675f,
                0.4663f,
                0.3417f,
                0.3081f,
                0.4220f,
                1f,
                0.0099f,
            )
        (rgb.r + rgb.g + rgb.b).zip(expected.toList()).forEach { (measured, reference) ->
            assertNear(reference, measured, 0.002f)
        }
    }
}

/**
 * Mirrors iOS `RGBPlanesTests`, minus the two that pin Core Graphics rather than the pipeline: the
 * EXIF rotation (Coil applies it while decoding, before the pixels reach here) and the grayscale
 * color space (a decoded Android bitmap is always RGB).
 */
class RGBPlanesTest {
    @Test
    fun channelsAreReadAsBytesOverTwoFiftyFive() {
        val image = rgbImage(2, 1, listOf(listOf(255, 0, 51), listOf(0, 128, 255)))

        val planes = RGBPlanes.of(image, maxSide = 2)

        assertEquals(PlaneSize(2, 1), planes.size)
        assertEquals(listOf(255, 0), planes.r.bytes())
        assertEquals(listOf(0, 128), planes.g.bytes())
        assertEquals(listOf(51, 255), planes.b.bytes())
    }

    @Test
    fun longSideIsCappedAtMaxSide() {
        val image = rgbImage(8, 4, List(32) { listOf(100, 150, 200) })

        val planes = RGBPlanes.of(image, maxSide = 4)

        assertEquals(PlaneSize(4, 2), planes.size)
        assertEquals(List(8) { 100 }, planes.r.bytes())
        assertEquals(List(8) { 150 }, planes.g.bytes())
        assertEquals(List(8) { 200 }, planes.b.bytes())
    }

    @Test
    fun smallerImageIsReadAtItsOwnSize() {
        val image = rgbImage(3, 2, List(6) { listOf(0, 0, 0) })

        val planes = RGBPlanes.of(image, maxSide = 100)

        assertEquals(PlaneSize(3, 2), planes.size)
    }

    private fun FloatArray.bytes(): List<Int> = map { (it * 255).roundToInt() }
}

/** Mirrors iOS `ResamplingTests`. */
class ResamplingTest {
    private val fiveByThree =
        GrayPlane.of(
            PlaneSize(5, 3),
            intArrayOf(
                0,
                200,
                50,
                255,
                100,
                30,
                90,
                180,
                10,
                220,
                250,
                40,
                120,
                70,
                160,
            ),
        )

    @Test
    fun wholeBlocksAreAveraged() {
        val plane = GrayPlane.of(PlaneSize(4, 4), IntArray(16) { it * 16 })

        val resized = plane.resized(PlaneSize(2, 2))

        assertEquals(PlaneSize(2, 2), resized.size)
        assertArrayEquals(intArrayOf(40, 72, 168, 200), resized.toInts())
    }

    @Test
    fun partlyCoveredPixelsCountByOverlap() {
        val resized = fiveByThree.resized(PlaneSize(3, 2))

        assertEquals(PlaneSize(3, 2), resized.size)
        assertArrayEquals(intArrayOf(71, 123, 153, 129, 105, 128), resized.toInts())
    }

    @Test
    fun bicubicShrinkMatchesPillow() {
        val resized = fiveByThree.bicubicResized(PlaneSize(3, 2))

        assertEquals(PlaneSize(3, 2), resized.size)
        assertArrayEquals(intArrayOf(68, 133, 154, 124, 95, 125), resized.toInts())
    }

    @Test
    fun bicubicUpscaleClampsOvershoot() {
        val plane = GrayPlane.of(PlaneSize(3, 2), intArrayOf(0, 255, 0, 255, 0, 255))

        val resized = plane.bicubicResized(PlaneSize(5, 4))

        assertEquals(PlaneSize(5, 4), resized.size)
        assertArrayEquals(
            intArrayOf(
                0,
                96,
                255,
                96,
                0,
                53,
                112,
                202,
                112,
                53,
                202,
                143,
                53,
                143,
                202,
                255,
                159,
                0,
                159,
                255,
            ),
            resized.toInts(),
        )
    }

    @Test
    fun mirroredIndexFoldsBackWithoutRepeatingTheEdge() {
        assertEquals(
            listOf(1, 0, 1, 2, 3, 2, 1, 0, 1, 2, 3, 2, 1, 0, 1, 2, 3, 2),
            (-7..10).map { ColorizationHelpers.mirroredIndex(it, 4) },
        )
        assertEquals(List(6) { 0 }, (-2..3).map { ColorizationHelpers.mirroredIndex(it, 1) })
    }

    @Test
    fun paddingMirrorsTheFramePastItsEdges() {
        val plane = GrayPlane.of(PlaneSize(4, 3), IntArray(12) { it + 1 })

        val padded = plane.padded(PlaneSize(9, 5))

        assertEquals(PlaneSize(9, 5), padded.size)
        assertArrayEquals(
            intArrayOf(
                1,
                2,
                3,
                4,
                3,
                2,
                1,
                2,
                3,
                5,
                6,
                7,
                8,
                7,
                6,
                5,
                6,
                7,
                9,
                10,
                11,
                12,
                11,
                10,
                9,
                10,
                11,
                5,
                6,
                7,
                8,
                7,
                6,
                5,
                6,
                7,
                1,
                2,
                3,
                4,
                3,
                2,
                1,
                2,
                3,
            ),
            padded.toInts(),
        )
    }
}

/** Mirrors iOS `ABPlanesTests`. */
class ABPlanesTest {
    @Test
    fun croppingKeepsTheTopLeftCorner() {
        val planes =
            ABPlanes(
                PlaneSize(4, 3),
                floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f),
                floatArrayOf(
                    -10f,
                    -20f,
                    -30f,
                    -40f,
                    -50f,
                    -60f,
                    -70f,
                    -80f,
                    -90f,
                    -100f,
                    -110f,
                    -120f,
                ),
            )

        val cropped = planes.cropped(PlaneSize(3, 2))

        assertEquals(PlaneSize(3, 2), cropped.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 5f, 6f, 7f), cropped.a, 0f)
        assertArrayEquals(floatArrayOf(-10f, -20f, -30f, -50f, -60f, -70f), cropped.b, 0f)
    }

    @Test
    fun bilinearUpscalingBlendsFromHalfPixelCenters() {
        val planes =
            ABPlanes(
                PlaneSize(2, 2),
                floatArrayOf(0f, 100f, 100f, 0f),
                floatArrayOf(-40f, 20f, 60f, -80f),
            )

        val resized = planes.bilinearResized(PlaneSize(4, 4))

        assertEquals(PlaneSize(4, 4), resized.size)
        assertArrayEquals(
            floatArrayOf(
                0f,
                25f,
                75f,
                100f,
                25f,
                37.5f,
                62.5f,
                75f,
                75f,
                62.5f,
                37.5f,
                25f,
                100f,
                75f,
                25f,
                0f,
            ),
            resized.a,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(
                -40f,
                -25f,
                5f,
                20f,
                -15f,
                -12.5f,
                -7.5f,
                -5f,
                35f,
                12.5f,
                -32.5f,
                -55f,
                60f,
                25f,
                -45f,
                -80f,
            ),
            resized.b,
            0f,
        )
    }

    @Test
    fun bilinearResizingKeepsTheAxesApart() {
        val planes =
            ABPlanes(
                PlaneSize(3, 2),
                floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f),
                floatArrayOf(-5f, -15f, -25f, 35f, 45f, 55f),
            )

        val resized = planes.bilinearResized(PlaneSize(6, 8))

        assertEquals(PlaneSize(6, 8), resized.size)
        assertArrayEquals(
            floatArrayOf(
                0f,
                2.5f,
                7.5f,
                12.5f,
                17.5f,
                20f,
                0f,
                2.5f,
                7.5f,
                12.5f,
                17.5f,
                20f,
                3.75f,
                6.25f,
                11.25f,
                16.25f,
                21.25f,
                23.75f,
                11.25f,
                13.75f,
                18.75f,
                23.75f,
                28.75f,
                31.25f,
                18.75f,
                21.25f,
                26.25f,
                31.25f,
                36.25f,
                38.75f,
                26.25f,
                28.75f,
                33.75f,
                38.75f,
                43.75f,
                46.25f,
                30f,
                32.5f,
                37.5f,
                42.5f,
                47.5f,
                50f,
                30f,
                32.5f,
                37.5f,
                42.5f,
                47.5f,
                50f,
            ),
            resized.a,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(
                -5f,
                -7.5f,
                -12.5f,
                -17.5f,
                -22.5f,
                -25f,
                -5f,
                -7.5f,
                -12.5f,
                -17.5f,
                -22.5f,
                -25f,
                0f,
                -1.875f,
                -5.625f,
                -9.375f,
                -13.125f,
                -15f,
                10f,
                9.375f,
                8.125f,
                6.875f,
                5.625f,
                5f,
                20f,
                20.625f,
                21.875f,
                23.125f,
                24.375f,
                25f,
                30f,
                31.875f,
                35.625f,
                39.375f,
                43.125f,
                45f,
                35f,
                37.5f,
                42.5f,
                47.5f,
                52.5f,
                55f,
                35f,
                37.5f,
                42.5f,
                47.5f,
                52.5f,
                55f,
            ),
            resized.b,
            0f,
        )
    }
}

internal fun assertNear(
    expected: Float,
    measured: Float,
    tolerance: Float,
) {
    assertTrue("$measured against $expected", abs(measured - expected) < tolerance)
}
