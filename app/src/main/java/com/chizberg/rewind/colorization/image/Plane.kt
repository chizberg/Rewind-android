package com.chizberg.rewind.colorization.image

/** Width and height of a plane. Port of iOS `PlaneSize`. */
data class PlaneSize(
    val width: Int,
    val height: Int,
) {
    val pixelCount: Int get() = width * height
}

/**
 * One channel of 8-bit values, row by row. iOS `Plane<UInt8>`; Kotlin has no unsigned arrays that
 * read as cheaply, so the bytes are stored signed and read back through [get].
 */
class GrayPlane(
    val size: PlaneSize,
    val values: ByteArray,
) {
    init {
        require(values.size == size.pixelCount)
    }

    operator fun get(index: Int): Int = values[index].toInt() and BYTE_MASK

    /** The values as 0...255 ints, for comparing and measuring. */
    fun toInts(): IntArray = IntArray(values.size) { get(it) }

    inline fun mapToFloat(transform: (Int) -> Float): FloatPlane =
        FloatPlane(
            size,
            FloatArray(size.pixelCount) {
                transform(get(it))
            },
        )

    inline fun mapToGray(transform: (Int) -> Int): GrayPlane =
        GrayPlane(
            size,
            ByteArray(size.pixelCount) {
                transform(get(it)).toByte()
            },
        )

    companion object {
        fun of(
            size: PlaneSize,
            values: IntArray,
        ): GrayPlane = GrayPlane(size, ByteArray(values.size) { values[it].toByte() })
    }
}

/** One channel of floats, row by row. iOS `Plane<Float>`. */
class FloatPlane(
    val size: PlaneSize,
    val values: FloatArray,
) {
    init {
        require(values.size == size.pixelCount)
    }

    inline fun map(transform: (Float) -> Float): FloatPlane =
        FloatPlane(
            size,
            FloatArray(size.pixelCount) {
                transform(values[it])
            },
        )

    inline fun mapToGray(transform: (Float) -> Int): GrayPlane =
        GrayPlane(
            size,
            ByteArray(size.pixelCount) {
                transform(values[it]).toByte()
            },
        )

    /**
     * numpy.percentile's default linear interpolation, read off a histogram instead of a sorted
     * copy: the rank the percentile asks for falls between two of the sorted values, and the answer
     * is the point between them. Sorting three million floats to read one of them costs about
     * forty times what binning them does. On a frame of that many pixels the two sorted values the
     * rank falls between land in the same bin, and the answer then comes out within a bin's width
     * of the exact one; on a handful of far apart values it would not. Values that are not finite
     * are left out, both because the bin index of one cannot be computed at all and because a
     * single stray pixel must not decide the answer for the whole frame.
     * https://numpy.org/doc/stable/reference/generated/numpy.percentile.html
     */
    fun percentile(
        percent: Double,
        bins: Int,
    ): Float {
        var minimum = Float.MAX_VALUE
        var maximum = -Float.MAX_VALUE
        var count = 0
        for (value in values) {
            if (!value.isFinite()) continue
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            count += 1
        }
        if (count == 0) return 0f
        if (maximum <= minimum) return minimum

        val width = (maximum - minimum) / bins
        val histogram = IntArray(bins)
        for (value in values) {
            if (!value.isFinite()) continue
            histogram[minOf(bins - 1, ((value - minimum) / width).toInt())] += 1
        }

        // The rank sits at p/100 of the way from the first sorted value to the last, and inside
        // the bin that holds it the values are taken to be evenly spread.
        val target = percent / 100 * (count - 1)
        var below = 0
        for ((index, bin) in histogram.withIndex()) {
            if ((below + bin).toDouble() <= target) {
                below += bin
                continue
            }
            val position = ((target - below) / bin).toFloat()
            return minimum + (index + position) * width
        }
        return maximum
    }
}

private const val BYTE_MASK = 0xFF
