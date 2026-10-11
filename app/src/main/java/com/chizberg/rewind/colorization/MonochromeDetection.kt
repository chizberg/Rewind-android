package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ArgbImage
import com.chizberg.rewind.colorization.image.RGBPlanes
import kotlin.math.sqrt

/**
 * Whether the photo has no color of its own — black and white, sepia, any single-hue print — and
 * so is worth offering to colorize. Port of iOS `isMonochrome(image:)`.
 *
 * Divergence: iOS answers `true` straight away for a CGImage in a monochrome color space; a
 * decoded Android bitmap is always RGB, so every photo takes the measurement below (which says
 * monochrome for a gray frame anyway).
 */
fun isMonochrome(image: ArgbImage): Boolean =
    deviationFromMonochrome(RGBPlanes.of(image, MAX_DOWNSAMPLED_DIMENSION)) < MONOCHROME_THRESHOLD

/**
 * Monochrome image pixels lie on a line in the RGB space, color image pixels are more like a
 * cloud, so they deviate from a line: ~0 for monochrome, ~0.1 for a regular color image, at most
 * ~2/3.
 */
private fun deviationFromMonochrome(planes: RGBPlanes): Float {
    require(planes.size.pixelCount > 2) { "Too few points to compute deviation" }

    // Each channel is shifted by its own average, so that the cloud of pixels sits around zero:
    // otherwise the line would be forced through black, and the line of a sepia image is not.
    val r = planes.r.centered()
    val g = planes.g.centered()
    val b = planes.b.centered()

    // Covariance: rr, gg and bb are how much each channel varies on its own, the rest how much a
    // pair of channels varies together.
    val inverseCount = 1f / planes.size.pixelCount
    val rr = dot(r, r) * inverseCount
    val gg = dot(g, g) * inverseCount
    val bb = dot(b, b) * inverseCount
    val rg = dot(r, g) * inverseCount
    val rb = dot(r, b) * inverseCount
    val gb = dot(g, b) * inverseCount

    val total = rr + gg + bb
    // A sum of squares of 0 is a single flat color: the matrix is empty and the rest is NaN.
    require(total > EPSILON) { "The image is a single flat color. Can't be handled" }

    val covariance =
        arrayOf(floatArrayOf(rr, rg, rb), floatArrayOf(rg, gg, gb), floatArrayOf(rb, gb, bb))

    // Power iteration: multiplying a vector by the matrix stretches it the most along the
    // direction of the largest spread, so repeating turns the vector towards that direction. The
    // gray axis is a good enough start for photos.
    var direction = normalized(floatArrayOf(1f, 1f, 1f))
    repeat(POWER_ITERATIONS) {
        val next = times(covariance, direction)
        if (length(next) <= EPSILON) return@repeat
        direction = normalized(next)
    }

    // How much of the spread that single direction explains.
    val explained = dot(direction, times(covariance, direction))
    return 1 - explained / total
}

private fun FloatArray.centered(): FloatArray {
    val mean = average().toFloat()
    return FloatArray(size) { this[it] - mean }
}

private fun dot(
    lhs: FloatArray,
    rhs: FloatArray,
): Float {
    var sum = 0f
    for (i in lhs.indices) sum += lhs[i] * rhs[i]
    return sum
}

private fun times(
    matrix: Array<FloatArray>,
    vector: FloatArray,
): FloatArray = FloatArray(vector.size) { row -> dot(matrix[row], vector) }

private fun length(vector: FloatArray): Float = sqrt(dot(vector, vector))

private fun normalized(vector: FloatArray): FloatArray {
    val length = length(vector)
    return FloatArray(vector.size) { vector[it] / length }
}

private const val MAX_DOWNSAMPLED_DIMENSION = 256
private const val POWER_ITERATIONS = 20
private const val MONOCHROME_THRESHOLD = 0.01f

/** iOS asks VGSL's `isApproximatelyGreaterThan(0)`; this is the "a hair above zero" it means. */
private const val EPSILON = 1e-6f
