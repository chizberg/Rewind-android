package com.chizberg.rewind.domain

import com.chizberg.rewind.network.dto.NetworkImage

/**
 * Domain image. Port of iOS `Model.Image`.
 *
 * Equality/hash are **by `cid` only** — the clustering diff depends on it. Deliberately a
 * hand-written class (not a `data class`), so equality can't silently start comparing all
 * fields. Divergence from iOS: carries `imagePath` (a path for Coil) instead of a lazy image
 * loader.
 */
class ModelImage(
    val cid: Int,
    val imagePath: String,
    val title: String,
    val dir: Direction?,
    /** Null for a photo without coordinates — only ever reached through a description link
     * (see [com.chizberg.rewind.network.dto.NetworkImageDetails.geo]). */
    val coordinate: Coordinate?,
    val date: ImageDate,
) {
    /**
     * iOS `forcedCoordinate`: the coordinate for paths that only ever see located photos — the
     * map's annotations and clustering (`getByBounds` returns nothing else), Street View (offered
     * only with a coordinate). iOS asserts in debug and falls back to `.zero`; ours falls back the
     * same way.
     */
    val forcedCoordinate: Coordinate get() =
        coordinate
            ?: Coordinate(latitude = 0.0, longitude = 0.0)

    constructor(ni: NetworkImage) : this(
        cid = ni.cid,
        imagePath = ni.file,
        title = ni.title,
        dir = Direction.fromString(ni.dir),
        coordinate = Coordinate.fromArray(ni.geo),
        date = ImageDate(year = ni.year, year2 = ni.year2),
    )

    override fun equals(other: Any?): Boolean =
        this === other || (other is ModelImage && cid == other.cid)

    override fun hashCode(): Int = cid

    fun copy(
        cid: Int = this.cid,
        imagePath: String = this.imagePath,
        title: String = this.title,
        dir: Direction? = this.dir,
        coordinate: Coordinate? = this.coordinate,
        date: ImageDate = this.date,
    ): ModelImage = ModelImage(cid, imagePath, title, dir, coordinate, date)
}
