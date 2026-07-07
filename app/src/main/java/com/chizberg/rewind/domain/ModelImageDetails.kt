package com.chizberg.rewind.domain

import com.chizberg.rewind.network.dto.NetworkImageDetails

/**
 * Domain image details. Port of iOS `Model.ImageDetails`.
 *
 * `direction` and `dir` are both derived from the same source field — this redundancy mirrors
 * the iOS struct verbatim.
 */
data class ModelImageDetails(
    val cid: Int,
    val title: String,
    val direction: Direction?,
    val coordinate: Coordinate,
    val date: ImageDate,
    val description: String?,
    val source: String?,
    val address: String?,
    val author: String?,
    val username: String,
    val file: String,
    val dir: Direction?,
) {
    constructor(ni: NetworkImageDetails) : this(
        cid = ni.cid,
        title = ni.title,
        direction = Direction.fromString(ni.dir),
        coordinate = Coordinate.fromArray(ni.geo),
        date = ImageDate(year = ni.year, year2 = ni.year2),
        description = ni.desc,
        source = ni.source,
        address = ni.address,
        author = ni.author,
        username = extractUsername(ni),
        file = ni.file,
        dir = Direction.fromString(ni.dir),
    )
}

private const val WATERSIGN_PREFIX = "uploaded by "

private fun extractUsername(ni: NetworkImageDetails): String {
    val watersign = ni.watersignText
    if (watersign != null && watersign.startsWith(WATERSIGN_PREFIX)) {
        return watersign.removePrefix(WATERSIGN_PREFIX)
    }
    return ni.user.name
}
