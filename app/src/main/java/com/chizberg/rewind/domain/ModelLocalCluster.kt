package com.chizberg.rewind.domain

import java.util.UUID

/**
 * Client-side merge of several nearby annotations. Port of iOS `Model.LocalCluster`.
 * Not to be confused with [ModelCluster] (server clusters loaded from the API).
 *
 * Equality/hash are **by [id] only** (mirrors iOS `==` by id). A cluster re-created after
 * absorbing new images gets a fresh [id], so it compares unequal to its predecessor — this drives
 * the add/remove annotation diff. Hand-written (not a data class) so equality can't drift to the
 * other fields.
 */
class ModelLocalCluster(
    val images: List<ModelImage>,
    val coordinate: Coordinate,
    val id: UUID = UUID.randomUUID(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ModelLocalCluster && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
