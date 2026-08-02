package com.chizberg.rewind.app

import android.graphics.Bitmap
import com.chizberg.rewind.features.comparison.CapturedImage

/**
 * The pixels behind a [CapturedImage] — a photo from CameraX, a copy of the Street View panorama,
 * or the rendered comparison canvas. The reducer never sees this type (it is `android.graphics`,
 * and comparison logic is JVM-only); the view that draws it and the exporter that encodes it do.
 */
class CapturedBitmap(
    val bitmap: Bitmap,
) : CapturedImage

/** The pixels of a capture on its way to the gallery or the share sheet. Every capture the app
 *  makes is a [CapturedBitmap]; anything else could only come from a test double, which never
 *  reaches here. */
fun CapturedImage.bitmap(): Bitmap =
    (this as? CapturedBitmap)?.bitmap ?: error("Unexpected capture payload: $this")
