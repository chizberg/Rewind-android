package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.colorization.stages.Boldness
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors iOS `ColorizationParityTests`: the parity frames through every stage before the model,
 * against the reference's statistics. The two model runs iOS adds when the packages are on the
 * host are not mirrored — the ONNX graphs were checked against torch where they were exported
 * (`tools/colorization/`), and ONNX Runtime's Android library does not load on the JVM.
 */
class ColorizationParityTest {
    private val reference = ParityReference.load()

    @Test
    fun grayFrame() {
        for (frame in ParityReference.frames) {
            val expected = reference.expected(frame, ColorizationModelId.DDColorLarge)

            val gray = reference.gray(frame)

            val tinted = frame == TINTED_FRAME
            val tolerance = if (tinted) TINTED_GRAY_TOLERANCE else ParityReference.MOMENT_TOLERANCE
            expected.check("1_to_gray_rgb", ParityStatistics.ofBytes(gray), tolerance)
        }
    }

    @Test
    fun preparedFrame() {
        for (frame in ParityReference.frames) {
            for (model in ColorizationModelId.entries) {
                checkPrepared(frame, model)
            }
        }
    }

    private fun checkPrepared(
        frame: String,
        model: ColorizationModelId,
    ) {
        val expected = reference.expected(frame, model)
        val tinted = frame == TINTED_FRAME
        val claheTolerance =
            when {
                !tinted -> ParityReference.MOMENT_TOLERANCE
                model == ColorizationModelId.DDColorLarge -> TINTED_CLAHE_TOLERANCE
                else -> TINTED_STRONG_CLAHE_TOLERANCE
            }
        val claheExtremeTolerance =
            if (tinted && model == ColorizationModelId.Eccv16) {
                TINTED_STRONG_CLAHE_EXTREME_TOLERANCE
            } else {
                ParityReference.EXTREME_TOLERANCE
            }

        val prepared = reference.prepared(frame, expected.claheClip, expected.levels)

        if (expected.levels) {
            val stretchTolerance =
                if (tinted) TINTED_STRETCH_TOLERANCE else ParityReference.MOMENT_TOLERANCE
            expected.check(
                "2_levels_rgb",
                ParityStatistics.ofBytes(Lab.neutralGray(prepared.lightness)),
                stretchTolerance,
            )
            val atTheBottom = share(prepared.lightness, 0f)
            val atTheTop = share(prepared.lightness, 100f)
            assertTrue("share at 0 $atTheBottom", atTheBottom in CRUSHED_SHARE)
            assertTrue("share at 100 $atTheTop", atTheTop in CRUSHED_SHARE)
        }

        expected.check(
            "3_clahe_rgb",
            ParityStatistics.ofBytes(prepared.gray),
            momentTolerance = claheTolerance,
            extremeTolerance = claheExtremeTolerance,
        )
        expected.check(
            "5_L",
            ParityStatistics.of(prepared.lightness.values),
            momentTolerance = LIGHTNESS_TOLERANCE,
        )
        val weight = Boldness.lightnessWeight(prepared.lightness)
        expected.check("7_lum_weight", ParityStatistics.of(weight.values))
    }

    private fun share(
        plane: FloatPlane,
        value: Float,
    ): Double = plane.values.count { it == value }.toDouble() / plane.size.pixelCount

    private companion object {
        const val LIGHTNESS_TOLERANCE = 0.15
        const val TINTED_FRAME = "166360"
        const val TINTED_GRAY_TOLERANCE = 0.25
        const val TINTED_STRETCH_TOLERANCE = 0.75
        const val TINTED_CLAHE_TOLERANCE = 1.25
        const val TINTED_STRONG_CLAHE_TOLERANCE = 2.5
        const val TINTED_STRONG_CLAHE_EXTREME_TOLERANCE = 2.5
        val CRUSHED_SHARE = 0.005..0.015
    }
}
