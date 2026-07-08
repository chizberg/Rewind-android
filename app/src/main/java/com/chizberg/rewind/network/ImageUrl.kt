package com.chizberg.rewind.network

private const val IMAGE_HOST = "https://img.pastvu.com"

/**
 * CDN URL for an image `file` path at the given [quality]. The `file` from the API carries
 * garbage query params (`?s=...`, API quirk #4) that must be stripped or the CDN 404s.
 *
 * Port of iOS `Network.image(path:quality:)` URL building. Divergence: yields a plain URL
 * string for Coil (Model.Image carries a path, not a lazy loader) instead of a `Request`.
 */
fun imageUrl(
    path: String,
    quality: ImageQuality,
): String {
    val cleanPath = path.substringBefore("?")
    return "$IMAGE_HOST/${quality.linkParam}/$cleanPath"
}
