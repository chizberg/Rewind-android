package com.chizberg.rewind.colorization.stages

import com.chizberg.rewind.colorization.ColorizationHelpers
import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.FloatPlane
import com.chizberg.rewind.colorization.image.PlaneSize
import com.chizberg.rewind.colorization.roundAway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * A bilateral filter over the Lab triple: a neighbor counts the less the more it differs from the
 * pixel at the center, so the model's color is averaged inside an object and not across its
 * outline. L is in the weight but never written. Port of iOS `EdgeAwareBlur`; the iOS README's
 * "Edge-aware blur" has the rest.
 * https://doi.org/10.1109/ICCV.1998.710815
 *
 * Divergence: iOS runs it as a Metal kernel (`LabBilateral.metal`); this is the same arithmetic on
 * the CPU, rows split across the default dispatcher's threads. Both exponentials come out of
 * tables, which is what OpenCV's own CPU filter does for a float frame: the spatial one per tap of
 * the window, the color one over the summed difference, linearly interpolated between bins 1/32
 * of a unit apart — finer than OpenCV's own table (4096 bins per channel over the frame's whole
 * range, about 16 per unit on a photo).
 */
object EdgeAwareBlur {
    /** cv2.bilateralFilter's two sigmas, in its units: sigmaColor over the summed channel
     *  difference, sigmaSpace over the distance. */
    private const val SIGMA_COLOR = 25f
    private const val SIGMA_SPACE = 12f

    /** How wide the window is on a frame of the reference's long side, in pixels. */
    private const val WINDOW_AT_REFERENCE = 15.0

    /** This port's own floor, above OpenCV's radius of at least 1: it binds at 373 px and below. */
    private const val MINIMUM_DIAMETER = 5

    /** The color exponential is looked up up to this far along the summed difference, past
     *  which it is under 1e-11 of the center's weight and counted as nothing. */
    private const val COLOR_TABLE_RANGE = 200f
    private const val COLOR_TABLE_BINS_PER_UNIT = 32

    private val colorTable: FloatArray by lazy {
        val coefficient = -0.5f / (SIGMA_COLOR * SIGMA_COLOR)
        val bins = (COLOR_TABLE_RANGE * COLOR_TABLE_BINS_PER_UNIT).toInt()
        FloatArray(bins + 2) { i ->
            val difference = i.toFloat() / COLOR_TABLE_BINS_PER_UNIT
            exp(difference * difference * coefficient)
        }
    }

    suspend fun apply(
        ab: ABPlanes,
        lightness: FloatPlane,
    ): ABPlanes {
        require(ab.size == lightness.size)
        val window = WINDOW_AT_REFERENCE * ab.size.scaleFromReference
        val diameter = max(MINIMUM_DIAMETER, odd(window))
        return filter(ab, lightness, diameter / 2)
    }

    /** Forced odd, so the filter has a center pixel to sit on. */
    private fun odd(value: Double): Int {
        val rounded = roundAway(value).toInt()
        return if (rounded % 2 == 0) rounded + 1 else rounded
    }

    private suspend fun filter(
        ab: ABPlanes,
        lightness: FloatPlane,
        radius: Int,
    ): ABPlanes =
        withContext(Dispatchers.Default) {
            val width = ab.size.width
            val height = ab.size.height
            // OpenCV's support is the circle inscribed in the diameter, not the whole square.
            val spaceCoefficient = -0.5f / (SIGMA_SPACE * SIGMA_SPACE)
            val offsetsX = ArrayList<Int>()
            val offsetsY = ArrayList<Int>()
            val spaceWeights = ArrayList<Float>()
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val distanceSquared = dx * dx + dy * dy
                    if (distanceSquared > radius * radius) continue
                    offsetsX += dx
                    offsetsY += dy
                    spaceWeights += exp(distanceSquared * spaceCoefficient)
                }
            }
            val taps =
                Taps(offsetsX.toIntArray(), offsetsY.toIntArray(), spaceWeights.toFloatArray())
            // Edge columns mirrored once rather than per tap.
            val mirroredColumns =
                IntArray(width + 2 * radius) {
                    ColorizationHelpers.mirroredIndex(it - radius, width)
                }
            val outA = FloatArray(ab.size.pixelCount)
            val outB = FloatArray(ab.size.pixelCount)
            val bands = max(1, Runtime.getRuntime().availableProcessors())
            val bandHeight = (height + bands - 1) / bands
            coroutineScope {
                (0 until bands)
                    .map { band ->
                        async {
                            val top = band * bandHeight
                            val bottom = min(height, top + bandHeight)
                            for (y in top until bottom) {
                                ensureActive()
                                filterRow(
                                    y,
                                    ab,
                                    lightness,
                                    taps,
                                    mirroredColumns,
                                    radius,
                                    outA,
                                    outB,
                                )
                            }
                        }
                    }.awaitAll()
            }
            ABPlanes(ab.size, outA, outB)
        }

    private class Taps(
        val dx: IntArray,
        val dy: IntArray,
        val spaceWeight: FloatArray,
    )

    /** One row of the kernel in `LabBilateral.metal`: every tap weighs by distance and by the
     *  summed absolute difference of all three channels, squared — what OpenCV does for a three
     *  channel float frame, not the euclidean distance one would assume. */
    @Suppress("LongParameterList")
    private fun filterRow(
        y: Int,
        ab: ABPlanes,
        lightness: FloatPlane,
        taps: Taps,
        mirroredColumns: IntArray,
        radius: Int,
        outA: FloatArray,
        outB: FloatArray,
    ) {
        val width = ab.size.width
        val height = ab.size.height
        val l = lightness.values
        val a = ab.a
        val b = ab.b
        val table = colorTable
        val tableLimit = table.size - 2
        for (x in 0 until width) {
            val index = y * width + x
            val centerL = l[index]
            val centerA = a[index]
            val centerB = b[index]
            var weightSum = 0f
            var sumA = 0f
            var sumB = 0f
            for (t in taps.dx.indices) {
                val row = ColorizationHelpers.mirroredIndex(y + taps.dy[t], height) * width
                val tap = row + mirroredColumns[x + taps.dx[t] + radius]
                val tapA = a[tap]
                val tapB = b[tap]
                val difference = abs(l[tap] - centerL) + abs(tapA - centerA) + abs(tapB - centerB)
                val position = difference * COLOR_TABLE_BINS_PER_UNIT
                val colorWeight =
                    if (position >= tableLimit) {
                        0f
                    } else {
                        val bin = position.toInt()
                        val fraction = position - bin
                        table[bin] + (table[bin + 1] - table[bin]) * fraction
                    }
                val weight = taps.spaceWeight[t] * colorWeight
                weightSum += weight
                sumA += weight * tapA
                sumB += weight * tapB
            }
            outA[index] = sumA / weightSum
            outB[index] = sumB / weightSum
        }
    }
}

/** The long side of the frames the reference measured its filter sizes on. */
private const val REFERENCE_LONG_SIDE = 1600.0

/**
 * How much larger this frame is than the one the reference measured on. Every length it states in
 * pixels is multiplied by this, so the color does not depend on how big the file happened to be.
 * Declared with the first filter that needed it and read by [Boldness] too, as on iOS.
 */
val PlaneSize.scaleFromReference: Double
    get() = max(width, height) / REFERENCE_LONG_SIDE
