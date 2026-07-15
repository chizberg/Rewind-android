package com.chizberg.rewind.features.map.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.chizberg.rewind.domain.RgbaColor
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds and caches the map's marker **bitmaps**. Android divergence from iOS's `MKAnnotationView`
 * subclasses: instead of live views that recolour themselves, we draw immutable `Bitmap`s on a
 * `Canvas`. The caller decides how to present each: individual image pins and overlap-cluster
 * bubbles go through the Compose `Clustering` layer as `Image`s (cheap — no image loading); server
 * and local clusters become `BitmapDescriptor` markers.
 *
 * Static icons are keyed by their resolved colours (+ count, + pin rotation) and cached in an
 * [LruCache]. Keying by the **resolved ARGB** rather than a year bucket keeps colours exact (no
 * visible quantization) while still bounding entries to the number of distinct on-screen tints.
 * Server-cluster thumbnails are per-image and not cached here (Coil owns the image cache).
 *
 * Sizes mirror the iOS annotation views (20×26 pin, 60×60 cluster, matching paddings).
 */
class AnnotationIconFactory(
    private val density: Float,
) {
    private val cache = LruCache<IconKey, Bitmap>(CACHE_ENTRIES)

    private enum class IconType { IMAGE, BUBBLE, SERVER_PLACEHOLDER }

    private data class IconKey(
        val type: IconType,
        val tintArgb: Int,
        val foregroundArgb: Int,
        val count: Int,
        val angleDeg: Int,
    )

    /**
     * A tinted teardrop with a soft [shadow]-coloured drop shadow, rotated to [angleDeg] (degrees
     * clockwise from north; 0 points up). Rotation is baked into the bitmap so the Compose
     * `Clustering` content can render it as a plain, correctly-sized `Image`. Single continuous
     * path (no circle+triangle seam), matching the shared pin outline on iOS.
     */
    fun pinBitmap(
        tint: RgbaColor,
        shadow: RgbaColor,
        angleDeg: Float,
    ): Bitmap {
        val bucket = angleDeg.toInt()
        return cached(IconKey(IconType.IMAGE, tint.argb, shadow.argb, 0, bucket)) {
            val upright = drawImagePin(tint, shadow)
            if (bucket == 0) upright else upright.rotated(bucket.toFloat())
        }
    }

    /** A tinted capsule carrying a [count] — used for both local clusters and overlap bubbles. */
    fun bubbleBitmap(
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap =
        cached(IconKey(IconType.BUBBLE, tint.argb, foreground.argb, count, 0)) {
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
            cached(IconKey(IconType.SERVER_PLACEHOLDER, tint.argb, foreground.argb, count, 0)) {
                drawServerCluster(null, tint, foreground, count)
            }
        } else {
            drawServerCluster(thumbnail, tint, foreground, count)
        }

    private fun cached(
        key: IconKey,
        draw: () -> Bitmap,
    ): Bitmap = cache.get(key) ?: draw().also { cache.put(key, it) }

    private fun Bitmap.rotated(degrees: Float): Bitmap =
        Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)

    // region drawing

    private fun px(dp: Float): Float = dp * density

    private fun newBitmap(
        width: Float,
        height: Float,
    ): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    private fun drawImagePin(
        tint: RgbaColor,
        shadow: RgbaColor,
    ): Bitmap {
        val w = px(PIN_W_DP)
        val h = px(PIN_H_DP)
        val pad = px(PIN_SHADOW_RADIUS_DP + PIN_SHADOW_DY_DP + 1f)
        val (bmp, canvas) = newBitmap(w + pad * 2, h + pad * 2)

        val path = teardropPath(w, h).apply { offset(pad, pad) }
        val fill =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.argb
                style = Paint.Style.FILL
                setShadowLayer(
                    px(PIN_SHADOW_RADIUS_DP),
                    0f,
                    px(PIN_SHADOW_DY_DP),
                    shadow.copy(alpha = PIN_SHADOW_ALPHA).argb,
                )
            }
        canvas.drawPath(path, fill)
        return bmp
    }

    /**
     * A single closed teardrop pointing up, filling `[0,w] × [0,h]`: a radius-`w/2` disc at the
     * bottom, its top pulled to an apex at `(w/2, 0)` along the two tangent lines from the apex.
     * The tangents meet the arc smoothly, so there is no circle/triangle seam.
     */
    private fun teardropPath(
        w: Float,
        h: Float,
    ): Path {
        val r = w / 2f
        val cx = w / 2f
        val cy = h - r
        val beta = acos(r / cy) // half-angle subtended by the tangent points at the apex
        val betaDeg = Math.toDegrees(beta.toDouble()).toFloat()
        val tx = r * sin(beta)
        val ty = r * cos(beta)
        return Path().apply {
            moveTo(cx, 0f) // apex
            lineTo(cx - tx, cy - ty) // left tangent point
            arcTo(
                RectF(cx - r, cy - r, cx + r, cy + r),
                TOP_ANGLE_DEG - betaDeg, // start at the left tangent point
                -(FULL_CIRCLE_DEG - 2f * betaDeg), // sweep the long way round the bottom
                false,
            )
            close() // right tangent point back to the apex
        }
    }

    private fun drawPill(
        text: String,
        background: RgbaColor,
        foreground: RgbaColor,
        textDp: Float,
        padHDp: Float,
        padVDp: Float,
    ): Bitmap {
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
        return bmp
    }

    private fun drawServerCluster(
        thumbnail: Bitmap?,
        tint: RgbaColor,
        foreground: RgbaColor,
        count: Int,
    ): Bitmap {
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
        return bmp
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
        const val TOP_ANGLE_DEG = 270f
        const val FULL_CIRCLE_DEG = 360f

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
