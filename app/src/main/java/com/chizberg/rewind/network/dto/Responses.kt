package com.chizberg.rewind.network.dto

import kotlinx.serialization.Serializable

/**
 * `photo.getByBounds` response envelope. Port of the private `RawResponse` in iOS Request.swift.
 * `photos`/`clusters` are nullable (absent key -> `null`); an empty result sends `[]`.
 * The Request layer (M3) coalesces both to empty lists.
 */
@Serializable
data class ByBoundsResponse(
    val result: ClusteredImages,
) {
    @Serializable
    data class ClusteredImages(
        val photos: List<NetworkImage>? = null,
        val clusters: List<NetworkCluster>? = null,
    )
}

/** `photo.giveForPage` response envelope. Port of `RawResponse` in iOS Request.swift. */
@Serializable
data class GiveForPageResponse(
    val result: Result,
) {
    @Serializable
    data class Result(
        val photo: NetworkImageDetails,
    )
}
