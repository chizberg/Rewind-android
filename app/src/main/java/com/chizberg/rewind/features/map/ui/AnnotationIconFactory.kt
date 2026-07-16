package com.chizberg.rewind.features.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.tracing.trace
import com.chizberg.rewind.R
import com.chizberg.rewind.domain.RgbaColor
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * Builds and caches the map's marker **bitmaps**. Android divergence from iOS's `MKAnnotationView`
 * subclasses: instead of live views that recolour themselves, we draw immutable `Bitmap`s on a
 * `Canvas`. The overlay then renders each as a plain `Image`; server-cluster thumbnails carry the
 * loaded photo.
 *
 * The image pin is rendered from the [R.drawable.ic_image_pin] VectorDrawable — the iOS
 * `imageAnnotationIcon.svg` circle+triangle merged into one path — tinted per-year and given a soft
 * drop shadow. The cluster bubble and server disc are still drawn procedurally on a [Canvas].
 *
 * Static icons are keyed by their resolved colours (+ count) and cached in an [LruCache]. Keying
 * by the **resolved ARGB** rather than a year bucket keeps colours exact (no visible quantization)
 * while still bounding entries to the number of distinct on-screen tints. Pin direction is NOT part
 * of the key: rotation is the renderer's job (a free `graphicsLayer` transform), so one upright pin
 * per tint serves every angle instead of multiplying the cache ×360.
 * Server-cluster thumbnails are per-image and not cached here (Coil owns the image cache).
 *
 * Thread-safe: the overlay rasterizes icons on background threads (a load landing on camera idle
 * composes dozens of markers at once — drawing their icons on the main thread stuttered the
 * appear animation). [LruCache] is internally synchronized; misses are single-flight per key (a
 * dense wave asks for the same icon from many markers — only the first caller draws); the
 * mutated-per-draw pin drawable is per-thread, so distinct pins rasterize in parallel.
 *
 * Every raster emits a systrace section (`Rewind:pinRaster` / `Rewind:pillRaster` /
 * `Rewind:clusterRaster`), so a Perfetto capture attributes icon CPU per type and thread.
 *
 * Sizes mirror the iOS annotation views (20×26 pin, 60×60 cluster, matching paddings).
 */
class AnnotationIconFactory(
    private val context: Context,
    private val density: Float,
) {
    private val cache = LruCache<IconKey, Bitmap>(CACHE_ENTRIES)

    /** Padding around the pin so its drop shadow isn't clipped at the bitmap edge. */
    private val pinShadowPad = px(PIN_SHADOW_RADIUS_DP + PIN_SHADOW_DY_DP + 1f)

    /**
     * The single-path pin VectorDrawable ([R.drawable.ic_image_pin]), one lazily-inflated
     * instance per rasterizing thread: it is mutated per draw (tint + bounds), and a single
     * shared instance would serialize every pin draw of a dense wave behind one lock.
     */
    private val pinDrawable =
        ThreadLocal.withInitial {
            checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_image_pin)).mutate()
        }

    private enum class IconType { IMAGE, BUBBLE, SERVER_PLACEHOLDER }

    private data class IconKey(
        val type: IconType,
        val tintArgb: Int,
        val foregroundArgb: Int,
        val count: Int,
    )

    /**
     * A tinted pin with a soft [shadow]-coloured drop shadow, pointing up. Direction rotation is
     * applied by the renderer (`Modifier.rotate` on the GPU layer), never baked in here. The shape
     * comes from the single-path [R.drawable.ic_image_pin] asset (the iOS `imageAnnotationIcon.svg`,
     * merged to one path).
     */
    fun pinBitmap(
        tint: RgbaColor,
        shadow: RgbaColor,
    ): Bitmap =
        cached(IconKey(IconType.IMAGE, tint.argb, shadow.argb, 0)) {
            drawImagePin(tint, shadow)
        }

    /** A tinted capsule carrying a [count] — used for both local clusters and overlap bubbles. */
    fun bubbleBitmap(
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap =
        cached(IconKey(IconType.BUBBLE, tint.argb, foreground.argb, count)) {
            drawPill(count.toString(), tint, foreground, PILL_TEXT_DP, PILL_PAD_H_DP, PILL_PAD_V_DP)
        }

    /**
     * A server cluster: a ringed disc with a count badge. With [thumbnail] `null` it shows the
     * translucent tint placeholder; the loaded image then snaps into the same frame. The
     * placeholder is cached; the thumbnail variant is per-image and built fresh.
     */
    fun serverClusterBitmap(
        thumbnail: Bitmap?,
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap =
        if (thumbnail == null) {
            cached(IconKey(IconType.SERVER_PLACEHOLDER, tint.argb, foreground.argb, count)) {
                drawServerCluster(null, tint, foreground, count)
            }
        } else {
            drawServerCluster(thumbnail, tint, foreground, count)
        }

    // Cache peeks: a hit lets the overlay show the icon synchronously instead of spending a
    // coroutine (and a blank frame) on async production; a miss falls back to drawing off-main.

    /** The already-cached upright pin for the colours, or null. */
    fun cachedPinBitmap(
        tint: RgbaColor,
        shadow: RgbaColor,
    ): Bitmap? = cache.get(IconKey(IconType.IMAGE, tint.argb, shadow.argb, 0))

    /** The already-cached count capsule, or null. */
    fun cachedBubbleBitmap(
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap? = cache.get(IconKey(IconType.BUBBLE, tint.argb, foreground.argb, count))

    /** The already-cached server-cluster placeholder (no thumbnail), or null. */
    fun cachedServerClusterPlaceholder(
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap? = cache.get(IconKey(IconType.SERVER_PLACEHOLDER, tint.argb, foreground.argb, count))

    /** Per-key draw locks for [cached]'s single-flight; entries are dropped once drawn. */
    private val inFlight = ConcurrentHashMap<IconKey, Any>()

    private fun cached(
        key: IconKey,
        draw: () -> Bitmap,
    ): Bitmap {
        cache.get(key)?.let { return it }
        // Single-flight per key: in a dense appearance wave many markers miss on the same icon
        // simultaneously — only the first caller draws, the rest wait briefly and read the cache.
        val lock = inFlight.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            cache.get(key)?.let { return it }
            return draw().also {
                cache.put(key, it)
                inFlight.remove(key)
            }
        }
    }

    // region drawing

    private fun px(dp: Float): Float = dp * density

    private fun newBitmap(
        width: Float,
        height: Float,
    ): Pair<Bitmap, Canvas> {
        // ceil, not truncate: a shape drawn out to the full float [0,width]×[0,height] — e.g. a
        // pill's rounded bottom/right — would otherwise lose its last sub-pixel to the smaller int
        // bitmap and render a straight (clipped) edge there.
        val bmp =
            Bitmap.createBitmap(
                ceil(width).toInt(),
                ceil(height).toInt(),
                Bitmap.Config.ARGB_8888,
            )
        return bmp to Canvas(bmp)
    }

    /**
     * Renders the [pinDrawable] tinted to [tint] into a bitmap and casts a soft [shadow]-coloured
     * drop shadow beneath it. The shadow is the blurred alpha of the pin silhouette (offset down),
     * mirroring iOS's layer shadow on the same shape; padding ([pinShadowPad]) leaves room for it.
     */
    private fun drawImagePin(
        tint: RgbaColor,
        shadow: RgbaColor,
    ): Bitmap =
        trace("Rewind:pinRaster") {
            val w = ceil(px(PIN_W_DP)).toInt()
            val h = ceil(px(PIN_H_DP)).toInt()
            val pad = ceil(pinShadowPad).toInt()

            // The tinted pin silhouette, drawn from this thread's own VectorDrawable instance.
            val core = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pin = checkNotNull(pinDrawable.get())
            pin.setBounds(0, 0, w, h)
            pin.setTintList(ColorStateList.valueOf(tint.argb))
            pin.setTintMode(PorterDuff.Mode.SRC_IN)
            pin.draw(Canvas(core))

            // Compose the shadow (blurred silhouette alpha) then the pin, into a padded bitmap.
            val (bmp, canvas) = newBitmap((w + pad * 2).toFloat(), (h + pad * 2).toFloat())
            val blur =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    maskFilter =
                        BlurMaskFilter(px(PIN_SHADOW_RADIUS_DP), BlurMaskFilter.Blur.NORMAL)
                }
            val offset = IntArray(2)
            val shadowMask = core.extractAlpha(blur, offset)
            val shadowPaint = Paint().apply { color = shadow.copy(alpha = PIN_SHADOW_ALPHA).argb }
            canvas.drawBitmap(
                shadowMask,
                (pad + offset[0]).toFloat(),
                pad + offset[1] + px(PIN_SHADOW_DY_DP),
                shadowPaint,
            )
            canvas.drawBitmap(core, pad.toFloat(), pad.toFloat(), null)
            shadowMask.recycle()
            bmp
        }

    private fun drawPill(
        text: String,
        background: RgbaColor,
        foreground: RgbaColor,
        textDp: Float,
        padHDp: Float,
        padVDp: Float,
    ): Bitmap =
        trace("Rewind:pillRaster") {
            val textPaint = countTextPaint(foreground, textDp)
            val fm = textPaint.fontMetrics
            val w = textPaint.measureText(text) + px(padHDp) * 2f
            val h = (fm.descent - fm.ascent) + px(padVDp) * 2f
            val (bmp, canvas) = newBitmap(w, h)
            val radius = min(w, h) / 2f
            canvas.drawRoundRect(
                RectF(0f, 0f, w, h),
                radius,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background.argb },
            )
            canvas.drawText(text, w / 2f, textBaseline(textPaint, h / 2f), textPaint)
            bmp
        }

    private fun drawServerCluster(
        thumbnail: Bitmap?,
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap =
        trace("Rewind:clusterRaster") {
            val size = px(SERVER_CLUSTER_DP)
            val (bmp, canvas) = newBitmap(size, size)
            val center = size / 2f
            val ring = px(THUMBNAIL_RING_DP)
            val radius = center - ring / 2f

            // Translucent tint fill — the placeholder body, and a wash behind the image edges.
            canvas.drawCircle(
                center,
                center,
                radius,
                Paint(
                    Paint.ANTI_ALIAS_FLAG,
                ).apply { color = tint.copy(alpha = PLACEHOLDER_ALPHA).argb },
            )

            if (thumbnail != null) {
                val clip = Path().apply { addCircle(center, center, radius, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(
                    thumbnail,
                    centerCropSquare(thumbnail),
                    RectF(center - radius, center - radius, center + radius, center + radius),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                canvas.restore()
            }

            canvas.drawCircle(
                center,
                center,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tint.argb
                    style = Paint.Style.STROKE
                    strokeWidth = ring
                },
            )

            val badge =
                drawPill(
                    count.toString(),
                    tint,
                    foreground,
                    BADGE_TEXT_DP,
                    BADGE_PAD_H_DP,
                    BADGE_PAD_V_DP,
                )
            if (badge.width <= size) {
                canvas.drawBitmap(badge, size - badge.width, size - badge.height, null)
            }
            bmp
        }

    private fun countTextPaint(
        foreground: RgbaColor,
        textDp: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground.argb
            textSize = px(textDp)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

    /** Y for `drawText` so the text is vertically centred on [cy] (drawText takes a baseline). */
    private fun textBaseline(
        paint: Paint,
        cy: Float,
    ): Float = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

    private fun centerCropSquare(bitmap: Bitmap): Rect {
        val side = min(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    // endregion

    private val RgbaColor.argb: Int
        get() =
            Color.argb(
                (alpha * COLOR_MAX).toInt(),
                (red * COLOR_MAX).toInt(),
                (green * COLOR_MAX).toInt(),
                (blue * COLOR_MAX).toInt(),
            )

    private companion object {
        const val CACHE_ENTRIES = 256
        const val COLOR_MAX = 255.0

        const val PIN_W_DP = 20f
        const val PIN_H_DP = 26f
        const val PIN_SHADOW_RADIUS_DP = 3.5f
        const val PIN_SHADOW_DY_DP = 1.5f
        const val PIN_SHADOW_ALPHA = 0.35

        // Local cluster capsule (iOS MergedAnnotationView paddings (7,5)).
        const val PILL_TEXT_DP = 15f
        const val PILL_PAD_H_DP = 7f
        const val PILL_PAD_V_DP = 5f

        // Server cluster (iOS ClusterAnnotationView: 60×60, 3pt ring, badge padding (5,2.5)).
        const val SERVER_CLUSTER_DP = 60f
        const val THUMBNAIL_RING_DP = 3f
        const val PLACEHOLDER_ALPHA = 0.8
        const val BADGE_TEXT_DP = 13f
        const val BADGE_PAD_H_DP = 5f
        const val BADGE_PAD_V_DP = 2.5f
    }
}
