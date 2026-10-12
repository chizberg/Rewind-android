package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.image.RGBPlanes
import com.chizberg.rewind.colorization.stages.Boldness
import com.chizberg.rewind.colorization.stages.ChromaCeiling
import com.chizberg.rewind.colorization.stages.EdgeAwareBlur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Mirrors iOS `ColorizationCheckTests`. */
class ColorizationCheckTest {
    private val hues = 360
    private val coloredStride = 10
    private val size = PlaneSize(hues * coloredStride, 1)

    @Test
    fun aFrameThatCameBackAlmostGrayIsReportedAsNoColor() {
        val ab = planes { index -> (index % hues).toFloat() to FAINT_CHROMA }

        assertEquals(ColorizationCheck.NoColor, ab.checkColorization())
    }

    @Test
    fun oneHueOverTheWholeFrameIsReportedAsOnlyATint() {
        val ab = planes { TINT_HUE to STRONG_CHROMA }

        assertEquals(ColorizationCheck.OnlyTint, ab.checkColorization())
    }

    @Test
    fun colorsSpreadAroundTheCircleComeBackOk() {
        val ab = planes { index -> (index % hues).toFloat() to STRONG_CHROMA }

        assertEquals(ColorizationCheck.Ok, ab.checkColorization())
    }

    @Test
    fun aTintSplitAcrossTheStartOfTheCircleIsStillATint() {
        val ab = planes { index -> (if (index % 2 == 0) 355f else 5f) to STRONG_CHROMA }

        assertEquals(ColorizationCheck.OnlyTint, ab.checkColorization())
    }

    @Test
    fun theGrayPixelsDoNotCountTowardsTheTint() {
        val ab =
            planes { index ->
                val colored = index % coloredStride == 0
                if (colored) (index % hues).toFloat() to STRONG_CHROMA else TINT_HUE to 1f
            }

        assertEquals(ColorizationCheck.Ok, ab.checkColorization())
    }

    @Test
    fun tooFewColoredPixelsToJudgeCountAsATint() {
        val ab =
            planes { index ->
                if (index < TOO_FEW_COLORED) {
                    index.toFloat() * hues / TOO_FEW_COLORED to STRONG_CHROMA
                } else {
                    TINT_HUE to 0f
                }
            }

        assertEquals(ColorizationCheck.OnlyTint, ab.checkColorization())
    }

    @Test
    fun theColorIsJudgedBeforeTheGainCanLiftItOverTheBound() =
        runBlocking {
            val model = FaintColorModel()
            val photo = grayImage(PHOTO_SIDE, PHOTO_SIDE, List(PHOTO_SIDE * PHOTO_SIDE) { 128 })

            val result = colorize(photo, model)

            assertEquals(ColorizationCheck.NoColor, result.check)
            val lightness = Lab.lightness(RGBPlanes.of(photo, PHOTO_SIDE))
            val ab = model.predict(Lab.neutralGray(lightness))
            val anchored = EdgeAwareBlur.apply(ab, lightness)
            val bolder = Boldness.apply(anchored, lightness, model.boldness)
            val capped = ChromaCeiling.apply(bolder, model.boldness)
            assertEquals(ColorizationCheck.Ok, capped.checkColorization())
        }

    @Test
    fun aRunOfAModelThatPredictsNoColorSaysSo() =
        runBlocking {
            val result = colorize(tinyPhoto(), ColorlessModel(claheClip = 1.0))

            assertEquals(ColorizationCheck.NoColor, result.check)
        }

    private fun planes(color: (Int) -> Pair<Float, Float>): ABPlanes {
        val pixels = List(size.pixelCount, color)
        return ABPlanes(
            size,
            pixels.map { (hue, chroma) -> chroma * cos(hue * PI.toFloat() / 180) }.toFloatArray(),
            pixels.map { (hue, chroma) -> chroma * sin(hue * PI.toFloat() / 180) }.toFloatArray(),
        )
    }

    private companion object {
        const val FAINT_CHROMA = 7f
        const val STRONG_CHROMA = 20f
        const val TINT_HUE = 40f
        const val TOO_FEW_COLORED = 40
        const val PHOTO_SIDE = 64
    }
}

/** Mirrors iOS `ColorizeTests`. */
class ColorizeTest {
    @Test
    fun colorlessPredictionComposesTheFrameNeutralGray() =
        runBlocking {
            val reference = ParityReference.load()
            for (frame in ParityReference.frames) {
                for (measuredModel in ColorizationModelId.entries) {
                    val image = reference.input(frame)
                    val model = ColorlessModel(reference.expected(frame, measuredModel).claheClip)

                    val colorized = colorize(image, model).image

                    val prepared = reference.prepared(frame, model.claheClip, levels = true)
                    val received = model.receivedGray!!
                    assertEquals(prepared.gray.size, received.size)
                    assertArrayEquals(prepared.gray.toInts(), received.toInts())
                    val composed = RGBPlanes.of(colorized, reference.longSide)
                    assertEquals(prepared.gray.size, composed.size)
                    val gray = Lab.neutralGray(prepared.lightness)
                    for (channel in listOf(composed.r, composed.g, composed.b)) {
                        val deviation =
                            channel.indices.maxOf {
                                abs(
                                    ColorizationHelpers.byte(channel[it]) - gray[it],
                                )
                            }
                        assertTrue("$frame $measuredModel deviates by $deviation", deviation <= 1)
                    }
                }
            }
        }

    @Test
    fun cancellingBeforeTheRunStartsLeavesTheModelUntouched() =
        runBlocking {
            val model = ColorlessModel(claheClip = 1.0)
            val cancelled = Job().apply { cancel() }

            try {
                withContext(cancelled) { colorize(tinyPhoto(), model) }
                fail("the run should have been cancelled")
            } catch (_: CancellationException) {
            }
            assertNull(model.receivedGray)
        }

    @Test
    fun cancellingDuringThePredictionSkipsTheStagesAfterIt() =
        runBlocking {
            val model = BlockingModel()

            val run = async(Dispatchers.Default) { colorize(tinyPhoto(), model) }
            model.isPredicting.await()
            run.cancel()
            model.finishPrediction()

            try {
                run.await()
                fail("the run should have been cancelled")
            } catch (_: CancellationException) {
            }
        }
}

/** Mirrors iOS `MonochromeDetectionTests`. */
class MonochromeDetectionTest {
    @Test
    fun sepiaGradientIsMonochrome() {
        val image =
            rgbImage(
                16,
                16,
                List(256) { index ->
                    val t = (index % 16 + index / 16) / 30f
                    listOf((60 + t * 180).toInt(), (40 + t * 160).toInt(), (20 + t * 120).toInt())
                },
            )

        assertTrue(isMonochrome(image))
    }

    @Test
    fun colorCloudIsNotMonochrome() {
        val image =
            rgbImage(
                16,
                16,
                List(256) { index ->
                    val x = index % 16
                    val y = index / 16
                    listOf(x * 17, y * 17, (x * 7 + y * 3) % 16 * 17)
                },
            )

        assertTrue(!isMonochrome(image))
    }
}

/** Mirrors iOS `WatermarkedImageTests`. */
class WatermarkedImageTest {
    @Test
    fun stitchingTheSplitContentBackGivesTheOriginal() {
        val original =
            listOf(
                listOf(255, 0, 51),
                listOf(0, 128, 255),
                listOf(17, 34, 68),
                listOf(136, 204, 85),
                listOf(240, 120, 60),
                listOf(5, 250, 190),
            )
        val image = rgbImage(2, 3, original)

        val split = splitWatermark(image, watermarkHeight = 1, contentHeight = 2)
        assertTrue(split.watermark != null)
        val stitched = split.stitched()

        assertEquals(2, stitched.width)
        assertEquals(3, stitched.height)
        assertEquals(original, stitched.rgbBytes())
    }

    @Test
    fun stripIsFittedToANarrowerContent() {
        val color = listOf(200, 120, 40)
        val stripColor = listOf(10, 20, 30)
        val source =
            WatermarkedImage(
                content = rgbImage(2, 1, listOf(color, color)),
                watermark = rgbImage(4, 2, List(8) { stripColor }),
            )

        val stitched = source.stitched()

        assertEquals(2, stitched.width)
        assertEquals(2, stitched.height)
        assertEquals(listOf(color, color, stripColor, stripColor), stitched.rgbBytes())
    }
}
