package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate

/**
 * A grid cell for local clustering. Port of iOS `ClusteringCell`. [size] (the cell's degree span
 * at a given zoom) is part of the identity, so cells at different zooms never collide as map keys.
 */
data class ClusteringCell(
    val latIndex: Int,
    val lonIndex: Int,
    val size: Double,
) {
    /** The cell's center coordinate. */
    val coordinate: Coordinate
        get() =
            Coordinate(
                latitude = latIndex * size + size / 2,
                longitude = lonIndex * size + size / 2,
            )
}
