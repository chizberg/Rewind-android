package com.chizberg.rewind.network

/**
 * Port of iOS `ImageQuality`. `linkParam` is the CDN path segment for each quality.
 * `Comparable` (low < medium < high) comes for free from the enum ordinal, as on iOS.
 */
enum class ImageQuality(
    val linkParam: String,
) {
    Low("s"),
    Medium("d"),
    High("a"),
}
