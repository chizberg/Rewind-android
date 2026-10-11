package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.stages.Boldness
import com.chizberg.rewind.colorization.stages.ChromaCeiling
import com.chizberg.rewind.colorization.stages.EdgeAwareBlur
import com.chizberg.rewind.colorization.stages.Levels
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Mirrors iOS `LevelsTests`. */
class LevelsTest {
    private val size = PlaneSize(401, 1)
    private val lastIndex = size.pixelCount - 1

    @Test
    fun theBandBetweenTheFirstAndTheLastPercentFillsTheScale() {
        val ramp = plane { ramp(it, 20f, 70f) }

        val stretched = Levels.stretch(ramp).values

        assertEquals(0f, stretched[3])
        assertEquals(100f, stretched[397])
        assertNear(0.2551f, stretched[5], TOLERANCE)
        assertNear(50f, stretched[200], TOLERANCE)
    }

    @Test
    fun aStrayPixelAtEachEndDoesNotSetTheRange() {
        val ramp =
            plane { index ->
                when (index) {
                    0 -> 0f
                    lastIndex -> 100f
                    else -> ramp(index, 30f, 60f)
                }
            }

        val stretched = Levels.stretch(ramp).values

        assertNear(24.4898f, stretched[100], TOLERANCE)
        assertNear(50f, stretched[200], TOLERANCE)
        assertEquals(0f, stretched[0])
        assertEquals(100f, stretched[lastIndex])
    }

    @Test
    fun aFrameOfOneToneComesBackAsItIs() {
        val flat = plane { 40f }

        val stretched = Levels.stretch(flat)

        assertArrayEquals(flat.values, stretched.values, 0f)
    }

    private fun ramp(
        index: Int,
        low: Float,
        high: Float,
    ): Float = low + (high - low) * index / lastIndex

    private fun plane(value: (Int) -> Float) = FloatPlane(size, FloatArray(size.pixelCount, value))

    private companion object {
        const val TOLERANCE = 0.05f
    }
}

/** Mirrors iOS `BoldnessTests`. */
class BoldnessTest {
    private val size = PlaneSize(200, 16)
    private val middleIndex = size.width * (size.height / 2) + size.width / 2
    private val lastIndex = size.pixelCount - 1

    @Test
    fun theWeightMapCarriesTheLightnessThroughTheBlur() {
        val mosaic =
            FloatPlane(
                size,
                FloatArray(size.pixelCount) { index ->
                    ((index % size.width + 13 * (index / size.width)) % 101).toFloat()
                },
            )

        val weight = Boldness.lightnessWeight(mosaic).values

        assertNear(0.566255f, weight[0], TOLERANCE)
        assertNear(0.605761f, weight[middleIndex], TOLERANCE)
        assertNear(0.883951f, weight[lastIndex], TOLERANCE)
    }

    @Test
    fun halfwayThroughTheGuardHalfTheGainIsLeft() {
        val withdrawn = Boldness.effectiveBoldness(BOLDNESS, castRatio = 0.85f)

        assertNear(1.25f, withdrawn, TOLERANCE)
    }

    @Test
    fun aSingleTintKeepsTheColorTheModelPredicted() {
        val tinted = planes { 3f to 4f }

        val bolder = Boldness.apply(tinted, flatLightness(), BOLDNESS)

        assertEquals(1f, Boldness.castRatio(tinted))
        assertArrayEquals(tinted.a, bolder.a, 0f)
        assertArrayEquals(tinted.b, bolder.b, 0f)
    }

    @Test
    fun aFrameThatLeansNowhereTakesTheWholeGain() {
        val balanced = planes { column -> (if (column < size.width / 2) SWING else -SWING) to 0f }

        val bolder = Boldness.apply(balanced, flatLightness(), BOLDNESS)

        assertEquals(0f, Boldness.castRatio(balanced))
        assertNear(SWING * BOLDNESS, bolder.a[0], TOLERANCE)
        assertNear(-SWING * BOLDNESS, bolder.a[lastIndex], TOLERANCE)
    }

    private fun flatLightness() = FloatPlane(size, FloatArray(size.pixelCount) { 50f })

    private fun planes(value: (Int) -> Pair<Float, Float>): ABPlanes {
        val pixels = List(size.pixelCount) { value(it % size.width) }
        return ABPlanes(
            size,
            pixels.map { it.first }.toFloatArray(),
            pixels.map { it.second }.toFloatArray(),
        )
    }

    private companion object {
        const val BOLDNESS = 1.5f
        const val SWING = 10f
        const val TOLERANCE = 0.0005f
    }
}

/** Mirrors iOS `ChromaCeilingTests`. */
class ChromaCeilingTest {
    private val size = PlaneSize(401, 1)
    private val lastIndex = size.pixelCount - 1

    @Test
    fun theFrameIsMeasuredWhereNumpyMeasuresIt() {
        val mixed = planes { index -> (index % 41) * 2.5f to (index * 7 % 23).toFloat() }

        assertNear(100.07997f, mixed.peakChroma, 0.05f)
    }

    @Test
    fun aFrameOverTheCeilingComesBackUnderIt() {
        val ramp = planes { it.toFloat() to 0f }

        val capped = ChromaCeiling.apply(ramp, boldness = 1f)

        assertNear(396f, ramp.peakChroma, 0.01f)
        assertNear(24.242424f, capped.a[lastIndex], 0.001f)
        assertNear(24f, capped.peakChroma, 0.001f)
    }

    @Test
    fun aFrameUnderTheCeilingKeepsTheColorTheModelPredicted() {
        val ramp = planes { it * 20f / 400f to 0f }

        val capped = ChromaCeiling.apply(ramp, boldness = 1f)

        assertArrayEquals(ramp.a, capped.a, 0f)
        assertArrayEquals(ramp.b, capped.b, 0f)
    }

    @Test
    fun aPixelWithoutAColorDoesNotTakeTheFrameWithIt() {
        val ramp = planes { index -> (if (index == 7) Float.NaN else index.toFloat()) to 0f }

        assertNear(396.01f, ramp.peakChroma, 0.05f)
    }

    private fun planes(value: (Int) -> Pair<Float, Float>): ABPlanes {
        val pixels = List(size.pixelCount, value)
        return ABPlanes(
            size,
            pixels.map { it.first }.toFloatArray(),
            pixels.map { it.second }.toFloatArray(),
        )
    }
}

/** Mirrors iOS `EdgeAwareBlurTests` — the same numbers out of the CPU filter as out of the Metal
 *  kernel. */
class EdgeAwareBlurTest {
    private val size = PlaneSize(1600, 16)
    private val boundary = 800
    private val leftOfBoundary = size.width * (size.height / 2) + boundary - 1
    private val rightOfBoundary = leftOfBoundary + 1

    @Test
    fun aFlatColorIsUnchangedWhateverTheLightness() =
        runTest {
            val lightness = FloatPlane(size, FloatArray(size.pixelCount) { (it % 100).toFloat() })
            val ab =
                ABPlanes(
                    size,
                    FloatArray(size.pixelCount) { 12f },
                    FloatArray(size.pixelCount) { -30f },
                )

            val smoothed = EdgeAwareBlur.apply(ab, lightness)

            assertTrue(smoothed.a.all { abs(it - 12) < 0.001 })
            assertTrue(smoothed.b.all { abs(it + 30) < 0.001 })
        }

    @Test
    fun aColorStepIsSmoothedAwayWhereTheLightnessIsFlat() =
        runTest {
            val smoothed = EdgeAwareBlur.apply(colorStep(), lightness(left = 50f, right = 50f))

            assertNear(0.7095f, smoothed.a[leftOfBoundary], TOLERANCE)
            assertNear(-0.7095f, smoothed.a[rightOfBoundary], TOLERANCE)
        }

    @Test
    fun aLightnessEdgeKeepsTheColorStepOnItsOwnSide() =
        runTest {
            val smoothed = EdgeAwareBlur.apply(colorStep(), lightness(left = 20f, right = 80f))

            assertNear(4.8411f, smoothed.a[leftOfBoundary], TOLERANCE)
            assertNear(-4.8411f, smoothed.a[rightOfBoundary], TOLERANCE)
        }

    private fun colorStep() =
        ABPlanes(
            size,
            byColumn { if (it < boundary) STEP else -STEP },
            FloatArray(size.pixelCount),
        )

    private fun lightness(
        left: Float,
        right: Float,
    ) = FloatPlane(size, byColumn { if (it < boundary) left else right })

    private fun byColumn(value: (Int) -> Float): FloatArray {
        val width = size.width
        return FloatArray(size.pixelCount) { value(it % width) }
    }

    private companion object {
        const val STEP = 5f
        const val TOLERANCE = 0.01f
    }
}
