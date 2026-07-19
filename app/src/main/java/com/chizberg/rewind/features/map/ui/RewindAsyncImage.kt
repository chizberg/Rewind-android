package com.chizberg.rewind.features.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chizberg.rewind.network.ImageQuality
import com.chizberg.rewind.network.imageUrl

/**
 * The app's shared Coil [ImageLoader] (OkHttp-backed). Provided at the map root; a single loader so
 * the strip, and later the details/list/comparison screens, hit one memory+disk cache. Port of the
 * design's app-level `RewindAsyncImage` loader ownership.
 */
val LocalRewindImageLoader =
    staticCompositionLocalOf<ImageLoader> { error("LocalRewindImageLoader not provided") }

/**
 * A PastVu image loaded from its path at the given [quality]. Port of iOS `RewindAsyncImage`:
 * builds the CDN URL via [imageUrl] and crossfades in. Center-crops to fill its bounds (iOS
 * `.aspectRatio(.fill)`).
 */
@Composable
fun RewindAsyncImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    quality: ImageQuality = ImageQuality.Medium,
) {
    val context = LocalPlatformContext.current
    AsyncImage(
        model =
            ImageRequest
                .Builder(context)
                .data(imageUrl(path, quality))
                .crossfade(true)
                .build(),
        contentDescription = contentDescription,
        imageLoader = LocalRewindImageLoader.current,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
