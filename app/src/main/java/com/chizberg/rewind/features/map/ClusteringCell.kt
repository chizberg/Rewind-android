package com.chizberg.rewind.features.map

import com.chizberg.rewind.domain.Coordinate

/**
 * A grid cell for local clustering. Port of iOS `ClusteringCell`. [size] (the cell's degree span at
 * a given zoom) is part of the identity, so cells at different zooms never collide as map keys.
 *
 * This is safe only because the size is **deterministic per rounded-zoom bucket**: LocalClustering
 * computes it once on bucket entry and reuses it (see `MapState.clusteringCellSpan`), so its bits
 * don't drift between loads at the same zoom the way a per-load recompute of the continuous span
 * would — that drift would make every cell a new key and duplicate every annotation.
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
