package com.chizberg.rewind.colorization

import com.chizberg.rewind.Fixture
import com.chizberg.rewind.colorization.image.ArgbImage
import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.colorization.image.RGBPlanes
import com.chizberg.rewind.colorization.stages.CLAHE
import com.chizberg.rewind.colorization.stages.Levels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The stage-by-stage reference the iOS port is pinned to (`fixtures/ios-parity/reference.json`,
 * byte-identical to iOS's), and the parity frames read the way the pipeline reads a photo. Port of
 * iOS `ParityReference`.
 */
class ParityReference private constructor(
    val longSide: Int,
    private val cases: JsonObject,
) {
    class Case(
        val claheClip: Double,
        val levels: Boolean,
        private val stages: JsonObject,
    ) {
        fun check(
            stage: String,
            measured: ParityStatistics,
            momentTolerance: Double = MOMENT_TOLERANCE,
            extremeTolerance: Double = EXTREME_TOLERANCE,
        ) {
            val expected = statistics(stage)
            assertClose("$stage mean", measured.mean, expected.mean, momentTolerance)
            assertClose(
                "$stage std",
                measured.standardDeviation,
                expected.standardDeviation,
                momentTolerance,
            )
            assertClose("$stage min", measured.minimum, expected.minimum, extremeTolerance)
            assertClose("$stage max", measured.maximum, expected.maximum, extremeTolerance)
        }

        private fun statistics(stage: String): ParityStatistics {
            val value = stages[stage]?.jsonObject ?: error("no $stage statistics in the reference")
            return ParityStatistics(
                mean = value.getValue("mean").jsonPrimitive.double,
                standardDeviation = value.getValue("std").jsonPrimitive.double,
                minimum = value.getValue("min").jsonPrimitive.double,
                maximum = value.getValue("max").jsonPrimitive.double,
            )
        }

        fun scalar(stage: String): Double =
            (stages[stage] as? JsonPrimitive)?.double ?: error("no $stage value in the reference")
    }

    fun expected(
        frame: String,
        model: ColorizationModelId,
    ): Case {
        val case =
            cases["$frame|${model.rawValue}"]?.jsonObject ?: error("no $frame case for $model")
        return Case(
            claheClip = case.getValue("clahe").jsonPrimitive.double,
            levels = case.getValue("levels").jsonPrimitive.boolean,
            stages = case.getValue("stages").jsonObject,
        )
    }

    fun input(frame: String): ArgbImage {
        val image = Fixture.stream("ios-parity/$frame-input.png").use { ImageIO.read(it) }
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return ArgbImage(image.width, image.height, pixels)
    }

    fun gray(frame: String): GrayPlane =
        Lab.neutralGray(Lab.lightness(RGBPlanes.of(input(frame), longSide)))

    fun prepared(
        frame: String,
        claheClip: Double,
        levels: Boolean,
    ): Prepared {
        val lightness = Lab.lightness(RGBPlanes.of(input(frame), longSide))
        val stretched = if (levels) Levels.stretch(lightness) else lightness
        return Prepared(CLAHE.apply(Lab.neutralGray(stretched), claheClip), stretched)
    }

    class Prepared(
        val gray: GrayPlane,
        val lightness: FloatPlane,
    )

    companion object {
        const val MOMENT_TOLERANCE = 0.05
        const val EXTREME_TOLERANCE = 1.0

        val frames = listOf("2209460", "2504212", "166360")

        fun load(): ParityReference {
            val root = Json.parseToJsonElement(Fixture.text("ios-parity/reference.json")).jsonObject
            return ParityReference(
                longSide = root.getValue("long_side").jsonPrimitive.int,
                cases = root.getValue("cases").jsonObject,
            )
        }
    }
}

/** Mean, population std, min and max of a stage, as the reference records them. */
data class ParityStatistics(
    val mean: Double,
    val standardDeviation: Double,
    val minimum: Double,
    val maximum: Double,
) {
    companion object {
        fun of(values: FloatArray): ParityStatistics {
            var sum = 0.0
            var sumOfSquares = 0.0
            var minimum = Double.MAX_VALUE
            var maximum = -Double.MAX_VALUE
            for (value in values) {
                val v = value.toDouble()
                sum += v
                sumOfSquares += v * v
                minimum = minOf(minimum, v)
                maximum = max(maximum, v)
            }
            val mean = sum / values.size
            return ParityStatistics(
                mean,
                sqrt(sumOfSquares / values.size - mean * mean),
                minimum,
                maximum,
            )
        }

        fun ofBytes(plane: GrayPlane): ParityStatistics {
            val values = FloatArray(plane.size.pixelCount) { plane[it].toFloat() }
            return of(values)
        }
    }
}

private fun assertClose(
    what: String,
    measured: Double,
    expected: Double,
    tolerance: Double,
) {
    assertTrue("$what $measured against $expected", abs(measured - expected) < tolerance)
}
