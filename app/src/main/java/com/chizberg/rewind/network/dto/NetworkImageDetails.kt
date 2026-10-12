package com.chizberg.rewind.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PastVu image-details DTO (`photo.giveForPage`). Port of iOS `Network.ImageDetails`.
 *
 * Divergence from iOS: iOS declares dozens of unused fields under `#if DEBUG` to keep its
 * strict decoder from breaking. Here [networkJson] has `ignoreUnknownKeys = true`, so we
 * declare only what we use; all extra keys are silently dropped.
 */
@Serializable
data class NetworkImageDetails(
    val cid: Int,
    val file: String,
    val title: String,
    val dir: String? = null,
    // Absent for a photo PastVu has no coordinates for (`giveForPage` omits the key; `getByBounds`
    // only ever returns located photos). Such photos are reached through description links.
    val geo: List<Double>? = null,
    val year: Int,
    val year2: Int,
    val desc: String? = null,
    val source: String? = null,
    val address: String? = null,
    val author: String? = null,
    val watersignText: String? = null,
    /** Height of the original image, without the watermark strip PastVu stitches under it. */
    val h: Int,
    /** Height of that strip at the original size. Both required, as on iOS: colorization cuts the
     *  strip off by them. */
    val waterh: Int,
    val user: NetworkUser,
)

/** Uploader info; `name` comes from the `disp` key. Port of iOS `Network.User`. */
@Serializable
data class NetworkUser(
    @SerialName("disp") val name: String,
)
