package com.chizberg.rewind.domain

import com.chizberg.rewind.network.dto.NetworkCluster

/**
 * Domain cluster: a preview image standing in for [count] photos at [coordinate].
 * Port of iOS `Model.Cluster`.
 */
data class ModelCluster(
    val preview: ModelImage,
    val coordinate: Coordinate,
    val count: Int,
) {
    constructor(nc: NetworkCluster) : this(
        // 🩼 The server sends the preview's geo reversed ([lon, lat]); flip it back.
        preview = ModelImage(nc.preview).let { it.copy(coordinate = it.coordinate.reversed()) },
        coordinate = Coordinate.fromArray(nc.geo),
        count = nc.count,
    )
}
