package com.chizberg.rewind.colorization.stages

import com.chizberg.rewind.colorization.ColorizationHelpers
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.colorization.lerp
import com.chizberg.rewind.colorization.roundAway
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Contrast Limited Adaptive Histogram Equalization (Zuiderveld, Graphics Gems IV, 1994): the frame
 * is cut into a grid of tiles, each tile's histogram is equalized on its own, the stretch of any
 * one level is capped, and every pixel blends the tables of the four tiles around it so the grid
 * does not show. Written after OpenCV's clahe.cpp step by step, because the reference runs
 * cv2.createCLAHE(clipLimit, tileGridSize: (8, 8)) on the L channel of an 8-bit Lab frame and
 * every parity number carries its exact arithmetic. Port of iOS `CLAHE`.
 * https://github.com/opencv/opencv/blob/4.x/modules/imgproc/src/clahe.cpp
 */
@Suppress("ktlint:standard:class-naming")
object CLAHE {
    private const val GRID_SIZE = 8
    private const val HISTOGRAM_SIZE = 256

    /** [clip] is OpenCV's clipLimit: how many times the average bin one histogram bin may hold.
     *  The gray frame is neutral, so RGB -> Lab -> L is one table lookup and the way back another. */
    fun apply(
        gray: GrayPlane,
        clip: Double,
    ): GrayPlane {
        val lightness = gray.mapToGray { Lab.grayToLightnessByte[it] }
        return equalize(lightness, clip).mapToGray { Lab.lightnessByteToGray[it] }
    }

    @Suppress("LongMethod")
    private fun equalize(
        lightness: GrayPlane,
        clip: Double,
    ): GrayPlane {
        val width = lightness.size.width
        val height = lightness.size.height

        // OpenCV pads the frame up to a whole number of tiles, and on both axes as soon as either
        // one needs it, so a width already divisible by 8 still gains 8 pixels, one per tile. The
        // pad is never built: `mirroredIndex` reads it out of the original.
        val fitsWholeTiles = width % GRID_SIZE == 0 && height % GRID_SIZE == 0
        val paddedWidth = if (fitsWholeTiles) width else width + GRID_SIZE - width % GRID_SIZE
        val paddedHeight = if (fitsWholeTiles) height else height + GRID_SIZE - height % GRID_SIZE
        val tileWidth = paddedWidth / GRID_SIZE
        val tileHeight = paddedHeight / GRID_SIZE
        val tileArea = tileWidth * tileHeight

        // tileArea / histogramSize is the average bin; static_cast<int> in OpenCV, so truncation,
        // then at least one count per bin.
        val limit = max((clip * tileArea / HISTOGRAM_SIZE).toInt(), 1)

        // One 256-entry table per tile, stored flat in row-major tile order.
        val tables = IntArray(GRID_SIZE * GRID_SIZE * HISTOGRAM_SIZE)
        val histogram = IntArray(HISTOGRAM_SIZE)
        for (tileY in 0 until GRID_SIZE) {
            for (tileX in 0 until GRID_SIZE) {
                histogram.fill(0)
                for (y in 0 until tileHeight) {
                    val row =
                        ColorizationHelpers.mirroredIndex(tileY * tileHeight + y, height) * width
                    for (x in 0 until tileWidth) {
                        val column = ColorizationHelpers.mirroredIndex(tileX * tileWidth + x, width)
                        histogram[lightness[row + column]] += 1
                    }
                }
                fillTable(
                    histogram,
                    limit,
                    tileArea,
                    tables,
                    (tileY * GRID_SIZE + tileX) * HISTOGRAM_SIZE,
                )
            }
        }

        // The reciprocals are OpenCV's, a division would land some boundary pixels one tile over.
        val inverseTileWidth = 1 / tileWidth.toFloat()
        val inverseTileHeight = 1 / tileHeight.toFloat()
        val tileColumns = Array(width) { x -> neighborTiles(x * inverseTileWidth) }
        val equalized = ByteArray(lightness.size.pixelCount)
        for (y in 0 until height) {
            val tileRows = neighborTiles(y * inverseTileHeight)
            for (x in 0 until width) {
                val tileColumn = tileColumns[x]
                val value = lightness[y * width + x]

                fun table(
                    row: Int,
                    column: Int,
                ): Float = tables[(row * GRID_SIZE + column) * HISTOGRAM_SIZE + value].toFloat()
                val fromFirstRow =
                    lerp(
                        tileColumn.weight,
                        table(tileRows.first, tileColumn.first),
                        table(tileRows.first, tileColumn.second),
                    )
                val fromSecondRow =
                    lerp(
                        tileColumn.weight,
                        table(tileRows.second, tileColumn.first),
                        table(tileRows.second, tileColumn.second),
                    )
                val blended = lerp(tileRows.weight, fromFirstRow, fromSecondRow)
                // Halves round away from zero, where OpenCV's saturate_cast rounds them to even:
                // about 0.2% of pixels come out one level lighter, the parity mean moves by 0.002.
                equalized[y * width + x] = roundAway(blended).toInt().toByte()
            }
        }
        return GrayPlane(lightness.size, equalized)
    }

    /** One tile's table: clip the histogram at [limit], hand the clipped counts back to all bins,
     *  then integrate. The running sum scaled to 0...255 is the equalization map. */
    private fun fillTable(
        histogram: IntArray,
        limit: Int,
        tileArea: Int,
        tables: IntArray,
        offset: Int,
    ) {
        var clipped = 0
        for (i in 0 until HISTOGRAM_SIZE) {
            if (histogram[i] > limit) {
                clipped += histogram[i] - limit
                histogram[i] = limit
            }
        }

        // OpenCV's redistribution, reproduced literally: an even share to every bin, and the
        // remainder, under 256 counts, one per bin at a stride rather than onto the first bins.
        val batch = clipped / HISTOGRAM_SIZE
        var residual = clipped - batch * HISTOGRAM_SIZE
        for (i in 0 until HISTOGRAM_SIZE) histogram[i] += batch
        if (residual > 0) {
            val step = max(HISTOGRAM_SIZE / residual, 1)
            var i = 0
            while (i < HISTOGRAM_SIZE && residual > 0) {
                histogram[i] += 1
                residual -= 1
                i += step
            }
        }

        val scale = (HISTOGRAM_SIZE - 1).toFloat() / tileArea
        var sum = 0
        for (i in 0 until HISTOGRAM_SIZE) {
            sum += histogram[i]
            tables[offset + i] = roundAway(sum * scale).toInt()
        }
    }

    /** The two tile centres a pixel lies between along one axis, and how far it is towards the
     *  second. The weight is taken before the indices are clamped, so a pixel before the first
     *  centre or past the last one gets the edge tile alone. */
    private class NeighborTiles(
        val first: Int,
        val second: Int,
        val weight: Float,
    )

    private fun neighborTiles(position: Float): NeighborTiles {
        val centered = position - HALF
        val first = floor(centered).toInt()
        return NeighborTiles(max(first, 0), min(first + 1, GRID_SIZE - 1), centered - first)
    }

    private const val HALF = 0.5f
}
