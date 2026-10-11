package com.chizberg.rewind.features.details.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.colorization.image.ArgbImage
import com.chizberg.rewind.ui.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Port of iOS `MagicTuning`, its `default` values. Lengths are iOS points, taken here as dp; times
 * are seconds.
 */
private object Magic {
    const val BLUR = 10f
    const val GLOW = 0.38f
    const val BLOB_SIZE = 0.25f
    const val BLOB_SPREAD = 0.36f
    const val BLOB_SPEED = 0.79f
    const val HUE_SPEED = 0.33f
    const val DUST_GRAY = 0.55f
    const val DUST_AMOUNT = 1f
    const val DUST_GAP = 60f
    const val DUST_SIZE = 2.2f
    const val DUST_LIFE = 6.2f
    const val DUST_SPEED = 0.39f
    const val DUST_WANDER = 51f
    const val DUST_FLOW = 595f
    const val SPARKLE_SHARE = 0.27f
    const val SPARKLE_LIFE = 0.75f
    const val EXPOSURE = 2f
    const val WIND = 120f
    const val EDGE = 400f
    const val DURATION = 0.46f

    // The layers' own constants (MagicBlobsLayer / MagicDustLayer / MagicBlur).
    const val BLOB_COUNT = 5
    const val SIDE_TO_RADIUS = 4f
    const val LIFE_VARIATION = 0.5f
    const val SIZE_VARIATION = 0.3f
    const val BLUR_SAMPLE_SIDE = 384
}

/** iOS `MagicBlobsLayer.falloff`: the alpha of a blob from its center out, at 0, ¼, ½, ¾ and 1. */
private val BlobFalloff = listOf(1f, 0.78f, 0.37f, 0.1f, 0f)

/**
 * The effect over the photo while the model runs. Port of iOS `MagicOverlay` / `MagicOverlayView`:
 * the photo blurred, colored blobs wandering over it with a cycling hue, gray dust drifting with a
 * few sparkles among it — revealed by a ripple out of the colorize button ([origin]) together with
 * a gust that pushes the dust away from it, and faded out when the run ends.
 *
 * Divergences: Core Animation's emitter and keyframe animations become one frame clock driving a
 * [Canvas]; iOS's sparkles are brighter than white (EDR), here they are white.
 */
@Composable
fun MagicOverlay(
    image: ArgbImage?,
    origin: Offset,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(0f) }
    var isShown by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(1f) }
    val gust = remember { Animatable(0f) }
    LaunchedEffect(isActive) {
        if (isActive) {
            val fresh = !isShown
            isShown = true
            if (fresh) {
                alpha.snapTo(1f)
                reveal.snapTo(0f)
                gust.snapTo(0f)
                launch { gust.animateTo(1f, tween(durationMs(), easing = LinearOutSlowInEasing)) }
                reveal.animateTo(1f, tween(durationMs(), easing = FastOutSlowInEasing))
            } else {
                alpha.animateTo(1f, tween(durationMs(), easing = FastOutSlowInEasing))
            }
        } else if (isShown) {
            alpha.animateTo(0f, tween(durationMs(), easing = FastOutSlowInEasing))
            isShown = false
        }
    }
    if (!isShown) return

    val blurred by produceBlurSample(image)
    BoxWithConstraints(
        modifier
            .graphicsLayer {
                this.alpha = alpha.value
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                drawRipple(origin, reveal.value)
            },
    ) {
        blurred?.let { sample ->
            Image(
                bitmap = sample,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(Magic.BLUR.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
        val density = LocalDensity.current.density
        val dust =
            remember(constraints.maxWidth, constraints.maxHeight, origin) {
                DustField(
                    Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                    origin,
                    density,
                )
            }
        var nanos by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { frame ->
                    val previous = nanos
                    nanos = frame - start
                    dust.step((nanos - previous) / NANOS_PER_SECOND)
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val time = nanos / NANOS_PER_SECOND
                drawBlobs(time)
                dust.draw(this, time, gust.value)
            }
        }
    }
}

private fun durationMs(): Int = (Magic.DURATION * MILLIS_PER_SECOND).roundToInt()

/** iOS `MagicBlur`: the photo brought down to a 384-px sample, which the blur then spreads. */
@Composable
private fun produceBlurSample(image: ArgbImage?) =
    androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image) {
        value =
            image?.let {
                withContext(Dispatchers.Default) {
                    val scale = Magic.BLUR_SAMPLE_SIDE.toFloat() / max(it.width, it.height)
                    Bitmap
                        .createScaledBitmap(
                            it.toBitmap(),
                            max(1, (it.width * scale).roundToInt()),
                            max(1, (it.height * scale).roundToInt()),
                            true,
                        ).asImageBitmap()
                }
            }
    }

/**
 * iOS `MagicRippleLayer` as a mask: opaque inside a circle around [origin] whose radius grows to
 * the farthest corner plus the soft edge, transparent beyond, with an [Magic.EDGE]-wide fade
 * between.
 */
private fun DrawScope.drawRipple(
    origin: Offset,
    progress: Float,
) {
    if (progress >= 1f) return
    val edge = Magic.EDGE * density
    val radius = progress * (farthestCorner(size, origin) + edge)
    if (radius <= 0f) {
        drawRect(Color.Transparent, blendMode = BlendMode.Clear)
        return
    }
    val inner = max(0f, radius - edge) / radius
    drawRect(
        brush =
            Brush.radialGradient(
                0f to Color.Black,
                inner to Color.Black,
                1f to Color.Transparent,
                center = origin,
                radius = radius,
            ),
        blendMode = BlendMode.DstIn,
    )
}

/** iOS `MagicBlobsLayer`: five radial blobs on looping paths, each hue a phase of one cycle. */
private fun DrawScope.drawBlobs(time: Float) {
    val radius = min(size.width, size.height) * Magic.BLOB_SIZE * Magic.SIDE_TO_RADIUS / 2
    for (index in 0 until Magic.BLOB_COUNT) {
        val center = blobCenter(index, time)
        val color = blobColor(index, time)
        // The stops are evenly spaced, so the colors alone place them.
        val colors = BlobFalloff.map { color.copy(alpha = it * Magic.GLOW) }
        drawCircle(Brush.radialGradient(colors, center = center, radius = radius), radius, center)
    }
}

@Suppress("MagicNumber")
private fun DrawScope.blobCenter(
    index: Int,
    time: Float,
): Offset {
    val drift = time * Magic.BLOB_SPEED
    val position = index.toFloat()
    val x = sin(drift * (0.7f + 0.13f * position) + position * 1.7f)
    val y = cos(drift * (0.5f + 0.11f * position) + position * 2.3f)
    return Offset(
        size.width * (0.5f + Magic.BLOB_SPREAD * x),
        size.height * (0.5f + Magic.BLOB_SPREAD * y),
    )
}

/** iOS `colors(of:at:)`: a cosine palette, the three channels a third of a turn apart. */
private fun blobColor(
    index: Int,
    time: Float,
): Color {
    val hue = (index.toFloat() / Magic.BLOB_COUNT + time * Magic.HUE_SPEED) % 1f

    fun channel(phase: Float) = 0.5f + 0.5f * cos(2 * PI.toFloat() * (hue + phase))
    return Color(channel(0f), channel(1f / 3), channel(2f / 3))
}

private fun farthestCorner(
    size: Size,
    from: Offset,
): Float =
    listOf(
        Offset.Zero,
        Offset(size.width, 0f),
        Offset(0f, size.height),
        Offset(size.width, size.height),
    ).maxOf { (it - from).getDistance() }

/**
 * iOS `MagicDustLayer`: dust and sparkles born anywhere over an area wider than the photo by the
 * reach of the drift, wandering at a slow speed in random directions, the whole field drifting
 * along a meandering path toward the bottom-leading corner and pushed outward once by the gust.
 */
private class DustField(
    private val size: Size,
    private val origin: Offset,
    private val density: Float,
) {
    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var age: Float,
        var life: Float,
        var radius: Float,
    )

    private val random = Random(seed = 0)
    private val flow = flowPath()
    private val margin = flow.fold(0f) { acc, point -> max(acc, max(abs(point.x), abs(point.y))) }
    private val area = Size(size.width + 2 * margin, size.height + 2 * margin)
    private val farthest = farthestCorner(size, origin)

    private val count =
        Magic.DUST_AMOUNT * area.width * area.height / (Magic.DUST_GAP * density).pow(2)
    private val dust =
        List(count.roundToInt()) { born(Magic.DUST_LIFE, Magic.LIFE_VARIATION, warm = true) }
    private val sparkles =
        List(
            (count * Magic.SPARKLE_SHARE).roundToInt(),
        ) { born(Magic.SPARKLE_LIFE, 0f, warm = true) }

    private fun born(
        life: Float,
        variation: Float,
        warm: Boolean,
    ): Particle {
        val speed = Magic.DUST_WANDER * Magic.DUST_SPEED * density
        val velocity = speed + speed * (random.nextFloat() * 2 - 1)
        val angle = random.nextFloat() * 2 * PI.toFloat()
        val particleLife = life + life * variation * (random.nextFloat() * 2 - 1)
        val diameter =
            Magic.DUST_SIZE * density * (1 + Magic.SIZE_VARIATION * (random.nextFloat() * 2 - 1))
        return Particle(
            x = random.nextFloat() * area.width - margin,
            y = random.nextFloat() * area.height - margin,
            vx = velocity * cos(angle),
            vy = velocity * sin(angle),
            // iOS backdates the emitter by a whole lifetime, so the field opens already full.
            age = if (warm) random.nextFloat() * particleLife else 0f,
            life = particleLife,
            radius = diameter / 2,
        )
    }

    fun step(seconds: Float) {
        advance(dust, seconds, Magic.DUST_LIFE, Magic.LIFE_VARIATION)
        advance(sparkles, seconds, Magic.SPARKLE_LIFE, 0f)
    }

    private fun advance(
        particles: List<Particle>,
        seconds: Float,
        life: Float,
        variation: Float,
    ) {
        for (particle in particles) {
            particle.age += seconds
            if (particle.age >= particle.life) {
                val next = born(life, variation, warm = false)
                particle.x = next.x
                particle.y = next.y
                particle.vx = next.vx
                particle.vy = next.vy
                particle.age = 0f
                particle.life = next.life
                particle.radius = next.radius
            } else {
                particle.x += particle.vx * seconds
                particle.y += particle.vy * seconds
            }
        }
    }

    fun draw(
        scope: DrawScope,
        time: Float,
        gust: Float,
    ) = with(scope) {
        val shift = flowOffset(time)
        val scale = 1 + gust * Magic.WIND * density / max(farthest, 1f)
        withTransform({
            translate(shift.x, shift.y)
            scale(scale, scale, pivot = origin - shift)
        }) {
            val gray = Color(Magic.DUST_GRAY, Magic.DUST_GRAY, Magic.DUST_GRAY)
            for (particle in dust) drawDot(particle, gray, 1f)
            for (particle in sparkles) {
                drawDot(
                    particle,
                    Color.White,
                    1 - particle.age / particle.life,
                )
            }
        }
    }

    private fun DrawScope.drawDot(
        particle: Particle,
        color: Color,
        alpha: Float,
    ) {
        drawCircle(
            color.copy(alpha = alpha.coerceIn(0f, 1f)),
            particle.radius,
            Offset(particle.x, particle.y),
        )
    }

    /** The drift as a function of time: iOS samples the same curve into a keyframe path. */
    private fun flowOffset(time: Float): Offset = meander(time * Magic.DUST_SPEED) - meander(0f)

    @Suppress("MagicNumber")
    private fun meander(breeze: Float): Offset {
        val toCorner = Offset(-origin.x, size.height - origin.y)
        val length = max(hypot(toCorner.x, toCorner.y), 1f)
        val along = Offset(toCorner.x / length, toCorner.y / length)
        val across = Offset(-along.y, along.x)
        val mx = (sin(0.17f * breeze) + 0.5f * sin(0.41f * breeze)) / 1.5f
        val my = (cos(0.13f * breeze) + 0.5f * cos(0.37f * breeze)) / 1.5f
        val reach = Magic.DUST_FLOW * density
        return Offset(
            reach * (mx * along.x + my * across.x),
            reach * (mx * along.y + my * across.y),
        )
    }

    /** The drift's farthest excursion, for the margin dust is born in. */
    private fun flowPath(): List<Offset> =
        (0..FLOW_SAMPLES).map { sample ->
            meander(
                sample / FLOW_SAMPLES_PER_SECOND * Magic.DUST_SPEED,
            ) -
                meander(0f)
        }
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MILLIS_PER_SECOND = 1000f
private const val FLOW_SAMPLES_PER_SECOND = 4f
private const val FLOW_SAMPLES = 1200
