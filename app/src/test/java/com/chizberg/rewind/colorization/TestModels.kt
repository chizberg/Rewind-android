package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.ArgbImage
import com.chizberg.rewind.colorization.image.GrayPlane
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** iOS `ColorlessModel`: predicts no color at all and remembers the frame it was handed. */
class ColorlessModel(
    override val claheClip: Double,
) : ColorizationModel {
    override val boldness = 1f

    @Volatile
    var receivedGray: GrayPlane? = null
        private set

    override suspend fun predict(gray: GrayPlane): ABPlanes {
        receivedGray = gray
        return ABPlanes(
            gray.size,
            FloatArray(gray.size.pixelCount),
            FloatArray(gray.size.pixelCount),
        )
    }
}

/** iOS `FaintColorModel`: a red-green split too faint for the check, with DDColor's gain. */
class FaintColorModel : ColorizationModel {
    override val claheClip = 1.0
    override val boldness = 1.5f

    override suspend fun predict(gray: GrayPlane): ABPlanes =
        ABPlanes(
            gray.size,
            FloatArray(gray.size.pixelCount) { index ->
                if (index % gray.size.width < gray.size.width / 2) CHROMA else -CHROMA
            },
            FloatArray(gray.size.pixelCount),
        )

    companion object {
        const val CHROMA = 6f
    }
}

/**
 * iOS `BlockingModel`: holds the prediction until [finishPrediction]. The wait ignores cancellation,
 * as iOS's `withCheckedContinuation` does, so the run only learns of it at its next check.
 */
class BlockingModel : ColorizationModel {
    override val claheClip = 1.0
    override val boldness = 1f

    val isPredicting = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    /** Whether the run had been cancelled by the time the prediction was released. */
    @Volatile
    var wasCancelled = false
        private set

    override suspend fun predict(gray: GrayPlane): ABPlanes {
        isPredicting.complete(Unit)
        withContext(NonCancellable) { release.await() }
        wasCancelled = !currentCoroutineContext().isActive
        return ABPlanes(
            gray.size,
            FloatArray(gray.size.pixelCount),
            FloatArray(gray.size.pixelCount),
        )
    }

    fun finishPrediction() {
        release.complete(Unit)
    }
}

/** iOS `makeRGBImage(width:height:pixels:)`. */
fun rgbImage(
    width: Int,
    height: Int,
    pixels: List<List<Int>>,
): ArgbImage =
    ArgbImage(
        width,
        height,
        IntArray(pixels.size) {
            ArgbImage.argb(pixels[it][0], pixels[it][1], pixels[it][2])
        },
    )

/** iOS `makeGrayImage(width:height:values:)`, as the RGB a decoded Android bitmap always is. */
fun grayImage(
    width: Int,
    height: Int,
    values: List<Int>,
): ArgbImage =
    ArgbImage(
        width,
        height,
        IntArray(values.size) {
            ArgbImage.argb(values[it], values[it], values[it])
        },
    )

/** iOS `makeTinyPhoto()`. */
fun tinyPhoto(): ArgbImage = grayImage(2, 2, listOf(0, 85, 170, 255))

fun ArgbImage.rgbBytes(): List<List<Int>> =
    pixels.map {
        listOf(ArgbImage.red(it), ArgbImage.green(it), ArgbImage.blue(it))
    }
