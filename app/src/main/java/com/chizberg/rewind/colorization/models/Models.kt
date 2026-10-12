package com.chizberg.rewind.colorization.models

import com.chizberg.rewind.colorization.ColorizationModel
import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.image.bicubicResized
import com.chizberg.rewind.colorization.image.padded
import com.chizberg.rewind.colorization.image.resized
import com.chizberg.rewind.colorization.roundAway
import kotlin.math.max

/**
 * One run of a converted graph. The runtime behind it (ONNX Runtime, in `app/`) is the platform's;
 * everything a model does before and after it is here, JVM-only, so the geometry is the same code
 * the parity frames go through.
 */
fun interface DDColorGraph {
    /** [square] is the fitted and padded gray frame, [DDColorLarge.INPUT_SIDE] on a side; the
     *  answer is ab at the same size. */
    suspend fun infer(square: GrayPlane): ABPlanes
}

fun interface ECCV16Graph {
    /** [lightness] is L of the squashed gray frame, [ECCV16.INPUT_SIDE] on a side; the answer is
     *  ab at the same size, decoded inside the graph with [rebalance] as the exponent on each
     *  bin's chroma. */
    suspend fun infer(
        lightness: FloatPlane,
        rebalance: Float,
    ): ABPlanes
}

/**
 * DDColor-large: gray frame in, ab out. Port of iOS `DDColorLarge`, minus Core ML.
 * https://arxiv.org/abs/2212.11613
 */
class DDColorLarge(
    private val graph: DDColorGraph,
) : ColorizationModel {
    /** The clip limit the reference measured for DDColor. */
    override val claheClip = 1.0

    /** The gain the reference measured for DDColor: left alone, its color is pale. */
    override val boldness = 1.5f

    /** ab at the gray frame's size: the frame fitted into the graph's square, reflect-padded for
     *  inference, cropped and scaled back after. The reference pads to a multiple of 32, which a
     *  fixed-shape graph cannot take. */
    override suspend fun predict(gray: GrayPlane): ABPlanes {
        val scale = INPUT_SIDE.toDouble() / max(gray.size.width, gray.size.height)
        val fitted =
            PlaneSize(
                max(1, roundAway(gray.size.width * scale).toInt()),
                max(1, roundAway(gray.size.height * scale).toInt()),
            )
        val square = PlaneSize(INPUT_SIDE, INPUT_SIDE)
        return graph
            .infer(gray.resized(fitted).padded(square))
            .cropped(fitted)
            .bilinearResized(gray.size)
    }

    companion object {
        /** Fixed input side the graph was traced for; the reference's working resolution. */
        const val INPUT_SIDE = 384
    }
}

/**
 * ECCV16 (Zhang et al.): lightness in, ab out, with the reference's decode of its 313 ab bins
 * inside the graph. Port of iOS `ECCV16`, minus Core ML.
 * https://arxiv.org/abs/1603.08511
 */
class ECCV16(
    private val graph: ECCV16Graph,
) : ColorizationModel {
    /** The clip limit the reference measured for ECCV16. */
    override val claheClip = 1.5

    /** No gain: this model's color strength is the rebalance exponent, already at the value the
     *  reference measured. */
    override val boldness = 1f

    /** ab at the gray frame's size: the frame squashed into the graph's square, proportions
     *  ignored as the upstream model and the reference run it, the ab scaled back after.
     *  https://github.com/richzhang/colorization/blob/master/colorizers/util.py */
    override suspend fun predict(gray: GrayPlane): ABPlanes {
        val square = PlaneSize(INPUT_SIDE, INPUT_SIDE)
        return graph
            .infer(Lab.lightnessOfGray(gray.bicubicResized(square)), REBALANCE)
            .bilinearResized(gray.size)
    }

    companion object {
        /** Fixed input side the graph was converted for; above 256 the model invents colors. */
        const val INPUT_SIDE = 256

        /** Exponent on each bin's chroma in the graph's decode; the plain mean (0) is washed out. */
        private const val REBALANCE = 2f
    }
}
