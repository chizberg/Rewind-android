package com.chizberg.rewind.network.dto

import kotlinx.serialization.Serializable

/**
 * PastVu image DTO. Port of iOS `Network.Image`.
 * `geo` is `[latitude, longitude]`. `file` may carry garbage query params (`?s=...`) —
 * kept verbatim here; stripped only when building the image URL (M3).
 */
@Serializable
data class NetworkImage(
    val cid: Int,
    val file: String,
    val title: String,
    val dir: String? = null,
    val geo: List<Double>,
    val year: Int,
    val year2: Int,
)
