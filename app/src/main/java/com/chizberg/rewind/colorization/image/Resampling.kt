package com.chizberg.rewind.colorization.image

import com.chizberg.rewind.colorization.ColorizationHelpers
import com.chizberg.rewind.colorization.roundAway
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Shrinks the gray frame to the model's input: each pixel is the average of the pixels it covers
 * (cv2.INTER_AREA). Port of iOS `Plane<UInt8>.resized(target:)`.
 * https://docs.opencv.org/4.x/da/d54/group__imgproc__transform.html#ga47a974309e9102f5f08231edc7e7529d
 */
fun GrayPlane.resized(target: PlaneSize): GrayPlane =
    resampled(
        target = target,
        columnTaps = areaTaps(size.width, target.width),
        rowTaps = areaTaps(size.height, target.height),
        betweenPasses = { it },
    )

/**
 * Squashes the gray frame into ECCV16's square the way the reference made that model's input:
 * Pillow's BICUBIC resize, rounded to bytes after the horizontal pass and after the vertical.
 * https://pillow.readthedocs.io/en/stable/handbook/concepts.html#filters
 */
fun GrayPlane.bicubicResized(target: PlaneSize): GrayPlane =
    resampled(
        target = target,
        columnTaps = cubicTaps(size.width, target.width),
        rowTaps = cubicTaps(size.height, target.height),
        betweenPasses = { roundAway(it).coerceIn(0f, BYTE_TOP) },
    )

/**
 * Fills the model's fixed input by mirroring the frame right and down, keeping its proportions
 * (cv2.BORDER_REFLECT_101).
 * https://docs.opencv.org/4.x/d2/de8/group__core__array.html#ga209f2f4869e304c82d07739337eae7c5
 */
fun GrayPlane.padded(target: PlaneSize): GrayPlane {
    require(target.width >= size.width && target.height >= size.height)
    val columns = IntArray(target.width) { ColorizationHelpers.mirroredIndex(it, size.width) }
    val padded = ByteArray(target.pixelCount)
    for (y in 0 until target.height) {
        val sourceRow = ColorizationHelpers.mirroredIndex(y, size.height) * size.width
        for (x in 0 until target.width) {
            padded[y * target.width + x] = values[sourceRow + columns[x]]
        }
    }
    return GrayPlane(target, padded)
}

/** Both resizes in two passes, columns into a buffer and then rows, as OpenCV and Pillow separate
 *  them; [betweenPasses] is what each library does to the buffer: floats kept, or bytes. */
private inline fun GrayPlane.resampled(
    target: PlaneSize,
    columnTaps: Array<Taps>,
    rowTaps: Array<Taps>,
    betweenPasses: (Float) -> Float,
): GrayPlane {
    val narrowed = FloatArray(target.width * size.height)
    for (y in 0 until size.height) {
        val sourceRow = y * size.width
        for (x in 0 until target.width) {
            val taps = columnTaps[x]
            var sum = 0f
            for (t in taps.indices.indices) {
                sum += get(sourceRow + taps.indices[t]) * taps.weights[t]
            }
            narrowed[y * target.width + x] = betweenPasses(sum)
        }
    }

    val resized = ByteArray(target.pixelCount)
    for (y in 0 until target.height) {
        val taps = rowTaps[y]
        for (x in 0 until target.width) {
            var sum = 0f
            for (t in taps.indices.indices) {
                sum += narrowed[taps.indices[t] * target.width + x] * taps.weights[t]
            }
            resized[y * target.width + x] = roundAway(sum).coerceIn(0f, BYTE_TOP).toInt().toByte()
        }
    }
    return GrayPlane(target, resized)
}

/** The source pixels feeding one output pixel along an axis, and each one's share of it; the
 *  shares of one output pixel sum to 1. Computed once per axis, reused on every row. */
private class Taps(
    val indices: IntArray,
    val weights: FloatArray,
)

private fun areaTaps(
    source: Int,
    destination: Int,
): Array<Taps> {
    val scale = source.toDouble() / destination
    return Array(destination) { index ->
        val start = index * scale
        val end = start + scale
        val first = floor(start).toInt()
        val last = min(ceil(end).toInt(), source)
        val covered = first until last
        Taps(
            IntArray(covered.count()) { first + it },
            FloatArray(covered.count()) { offset ->
                val position = first + offset
                val overlap = min(end, (position + 1).toDouble()) - max(start, position.toDouble())
                (overlap / scale).toFloat()
            },
        )
    }
}

/**
 * Pillow's bicubic weights along one axis: Keys' cubic kernel with a = -0.5, spelled as Pillow's
 * bicubic_filter, widened by the scale when shrinking so every source pixel under the output pixel
 * counts, the weights of each output pixel normalized to sum to 1.
 * https://doi.org/10.1109/TASSP.1981.1163711
 */
@Suppress("MagicNumber") // Keys' kernel and Pillow's support, as Pillow spells them.
private fun cubicTaps(
    source: Int,
    destination: Int,
): Array<Taps> {
    val a = -0.5
    val kernelSupport = 2.0

    fun cubic(distance: Double): Double {
        val x = abs(distance)
        return when {
            x < 1 -> ((a + 2) * x - (a + 3)) * x * x + 1
            x < kernelSupport -> (((x - 5) * x + 8) * x - 4) * a
            else -> 0.0
        }
    }
    val scale = source.toDouble() / destination
    val stretch = max(scale, 1.0)
    val support = kernelSupport * stretch
    return Array(destination) { index ->
        val center = (index + 0.5) * scale
        val first = max(0, (center - support + 0.5).toInt())
        val last = min((center + support + 0.5).toInt(), source)
        val count = max(0, last - first)
        val weights = DoubleArray(count) { cubic((first + it - center + 0.5) / stretch) }
        val total = weights.sum()
        Taps(
            IntArray(count) { first + it },
            FloatArray(count) { (weights[it] / total).toFloat() },
        )
    }
}

private const val BYTE_TOP = 255f
