package com.chizberg.rewind.colorization

import com.chizberg.rewind.colorization.image.ABPlanes
import com.chizberg.rewind.colorization.image.ArgbImage
import com.chizberg.rewind.colorization.image.GrayPlane
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.colorization.image.RGBPlanes
import com.chizberg.rewind.colorization.stages.Boldness
import com.chizberg.rewind.colorization.stages.CLAHE
import com.chizberg.rewind.colorization.stages.ChromaCeiling
import com.chizberg.rewind.colorization.stages.EdgeAwareBlur
import com.chizberg.rewind.colorization.stages.Levels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A model that turns the prepared gray frame into color. Port of iOS `ColorizationModel`.
 */
interface ColorizationModel {
    /** CLAHE's clip limit for the gray frame this model reads, the value the reference measured.
     *  https://docs.opencv.org/4.x/d6/db6/classcv_1_1CLAHE.html#abc15ca6784ab92c0d93e8b013fac191f */
    val claheClip: Double

    /** How much the color this model predicts is amplified afterwards; 1 for a model that needs
     *  none. */
    val boldness: Float

    /** ab in OpenCV's Lab units at the gray frame's size; how the frame gets into the graph and
     *  back is the model's own. */
    suspend fun predict(gray: GrayPlane): ABPlanes
}

/** iOS `ColorizationModelID`: the raw values are the manifest's and the settings' keys. */
@Serializable
enum class ColorizationModelId(
    val rawValue: String,
) {
    @SerialName("ddcolor-large")
    DDColorLarge("ddcolor-large"),

    @SerialName("eccv16")
    Eccv16("eccv16"),
    ;

    companion object {
        fun fromRawValue(value: String?): ColorizationModelId? {
            val match = { id: ColorizationModelId -> id.rawValue == value }
            return entries.firstOrNull(match)
        }
    }
}

/** What [colorize] hands back: the colorized photo and whether the model colored it or failed it. */
class ColorizationResult(
    val image: ArgbImage,
    val check: ColorizationCheck,
)

/**
 * The whole pipeline in the order of the iOS README's diagram, one stage a line. Port of iOS
 * `colorize(image:model:)`: the pixel work runs on the default dispatcher rather than on whatever
 * the tap came from, and every stage boundary is a cancellation point — a closed screen stops the
 * run at the next one.
 */
suspend fun colorize(
    image: ArgbImage,
    model: ColorizationModel,
): ColorizationResult =
    withContext(Dispatchers.Default) {
        ensureActive()
        val source = RGBPlanes.of(image, MAX_SIDE)
        val lightness = Lab.lightness(source)
        ensureActive()
        val stretched = Levels.stretch(lightness)
        ensureActive()
        val gray = CLAHE.apply(Lab.neutralGray(stretched), model.claheClip)
        ensureActive()

        val ab = model.predict(gray)
        ensureActive()

        val check = ab.checkColorization()
        ensureActive()

        // post-processing
        val anchored = EdgeAwareBlur.apply(ab, stretched)
        ensureActive()
        val bolder = Boldness.apply(anchored, stretched, model.boldness)
        ensureActive()
        val capped = ChromaCeiling.apply(bolder, model.boldness)
        ensureActive()

        ColorizationResult(Lab.rgb(stretched, capped).toArgbImage(), check)
    }

/** The cap on the long side the photo is read at; the model's own geometry starts from here. */
private const val MAX_SIDE = 2048
