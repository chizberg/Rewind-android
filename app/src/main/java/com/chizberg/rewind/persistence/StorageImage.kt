package com.chizberg.rewind.persistence

import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.Direction
import com.chizberg.rewind.domain.ImageDate
import com.chizberg.rewind.domain.ModelImage
import kotlinx.serialization.Serializable

/**
 * Persisted image DTO. Port of iOS `Storage.Image` (`Favorites/StorageImage.swift`): the on-disk
 * shape is intentionally decoupled from `ModelImage` — it carries only what survives a relaunch
 * (a plain `imagePath`, no lazy image loader), so it can evolve independently of the live
 * in-memory model.
 */
@Serializable
data class StorageImage(
    val cid: Int,
    val imagePath: String,
    val title: String,
    val dir: Direction?,
    val coordinate: Coordinate,
    val date: ImageDate,
)

/** iOS `Storage.Image(_ mi:)`: snapshot a live [ModelImage] into its persisted form. */
fun ModelImage.toStorageImage(): StorageImage =
    StorageImage(
        cid = cid,
        imagePath = imagePath,
        title = title,
        dir = dir,
        coordinate = coordinate,
        date = date,
    )

/**
 * iOS `Model.Image(_ storage:, image:)`: rehydrate a persisted image. The iOS initializer also
 * rebuilds a lazy `LoadableUIImage` from `imagePath`; ours carries only the path (the project-wide
 * Coil divergence), so there is nothing extra to reconstruct.
 */
fun StorageImage.toModelImage(): ModelImage =
    ModelImage(
        cid = cid,
        imagePath = imagePath,
        title = title,
        dir = dir,
        coordinate = coordinate,
        date = date,
    )
