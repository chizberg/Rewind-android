package com.chizberg.rewind.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PastVu cluster DTO. Port of iOS `Network.Cluster`.
 * Single-letter keys: `p` = preview, `c` = count. `geo` here is `[latitude, longitude]`,
 * but the preview's own `geo` arrives reversed (`[lon, lat]`) — handled in [ModelCluster].
 */
@Serializable
data class NetworkCluster(
    @SerialName("p") val preview: NetworkImage,
    val geo: List<Double>,
    @SerialName("c") val count: Int,
)
