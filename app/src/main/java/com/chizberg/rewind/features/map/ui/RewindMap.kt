package com.chizberg.rewind.features.map.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import com.chizberg.rewind.domain.Coordinate
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ModelCluster
import com.chizberg.rewind.domain.ModelLocalCluster
import com.chizberg.rewind.domain.Region
import com.chizberg.rewind.domain.Span
import com.chizberg.rewind.domain.zoom
import com.chizberg.rewind.features.map.AnnotationValue
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.makeMapModel
import com.chizberg.rewind.network.ImageQuality
import com.chizberg.rewind.network.RequestPerformer
import com.chizberg.rewind.network.RewindRemotes
import com.chizberg.rewind.network.imageUrl
import com.chizberg.rewind.network.invoke
import com.chizberg.rewind.network.okHttpRequestPerformer
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.clustering.Cluster
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val TAG = "RewindMap"

// M7 has no settings screen yet; the tint scheme is fixed to iOS's default until M13 wires it.
private val CurrentScheme = GradientScheme.Rewind

// Anchor every marker on its coordinate (iOS centres the annotation view on the point).
private val CenterAnchor = Offset(0.5f, 0.5f)

// Europe and Africa — mirrors the iOS RewindMap `initialRegion`.
private val InitialRegion =
    Region(
        center = Coordinate(latitude = 15.908556, longitude = 15.796728),
        span = Span(latitudeDelta = 76.225, longitudeDelta = 76.225),
    )

// A world-view start zoom for the initial region; the first camera idle replaces it (see Zoom.kt).
private const val INITIAL_ZOOM = 3f

// Server-cluster thumbnail render size in dp (mirrors AnnotationIconFactory's 60dp frame).
private const val SERVER_CLUSTER_DP = 60f

/**
 * The map surface. Port of iOS `RewindMap`. Each camera idle hands the visible region + zoom to
 * [makeMapModel], which debounces, loads, clusters, and cancels in-flight loads; the map renders
 * `state.annotations` declaratively. Markers are tinted by year via [CurrentScheme] and drawn as
 * cached bitmaps ([AnnotationIconFactory]); image pins rotate to the shot's bearing and server
 * clusters swap a Coil-loaded thumbnail in over a tinted placeholder. Composition root is
 * throwaway; AppGraph takes over in M9.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun RewindMap(modifier: Modifier = Modifier) {
    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(InitialRegion.center.toLatLng(), INITIAL_ZOOM)
        }

    // The reducer needs a single-threaded main scope; cancel it on leaving composition.
    val mapScope = remember { CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()) }
    DisposableEffect(Unit) { onDispose { mapScope.cancel() } }
    val mapModel =
        remember {
            val remotes = RewindRemotes(RequestPerformer(okHttpRequestPerformer(OkHttpClient())))
            makeMapModel(
                annotationsRemote = remotes.annotations,
                onLoadFailed = { Log.w(TAG, "annotation load failed", it) },
                scope = mapScope,
            )
        }
    val state by mapModel.state.collectAsStateWithLifecycle()

    val density = LocalDensity.current.density
    val icons = remember { AnnotationIconFactory(density) }
    // Cluster thumbnails render at 60dp; decode to that (in px) instead of full resolution.
    val thumbnailTargetPx = (SERVER_CLUSTER_DP * density).toInt()
    val context = LocalContext.current
    val imageLoader =
        remember {
            ImageLoader
                .Builder(context)
                .components { add(OkHttpNetworkFetcherFactory()) }
                .build()
        }
    val maxRange = state.filters.imageKind.maxRange

    // Camera idle (movement stopped + projection ready) → feed the region + zoom to the reducer.
    LaunchedEffect(mapModel, cameraPositionState) {
        snapshotFlow {
            if (cameraPositionState.isMoving) {
                null
            } else {
                cameraPositionState.projection?.visibleRegion?.latLngBounds
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { bounds ->
                mapModel(
                    MapAction.External.Map.RegionChanged(
                        region = bounds.toRegion(),
                        zoom = zoom(cameraPositionState.position.zoom),
                    ),
                )
            }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        // Mirror iOS RewindMapView: rotation and pitch (tilt) disabled; no Android-only zoom
        // buttons or map toolbar (iOS MapKit has no such controls).
        uiSettings =
            MapUiSettings(
                rotationGesturesEnabled = false,
                tiltGesturesEnabled = false,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
            ),
    ) {
        // Individual images → the android-maps-utils overlap-clustering layer (analog of iOS's
        // MapKit clusteringIdentifier). Cheap Compose content — no image loading. Grid local
        // clusters and (thumbnail-loading) server clusters are NOT clustered here; they render as
        // their own markers.
        val imageItems =
            remember(state.annotations, maxRange) {
                state.annotations.mapNotNull { it.image }.map { image ->
                    val tint = CurrentScheme.color(image.date.year, maxRange)
                    ImageClusterItem(
                        image = image,
                        tint = tint,
                        foreground = CurrentScheme.foreground(tint),
                        angleDeg = image.dir?.angleDegrees ?: 0f,
                    )
                }
            }
        Clustering(
            items = imageItems,
            onClusterClick = { false },
            onClusterItemClick = { false },
            clusterContent = { cluster -> BubbleContent(cluster, icons) },
            clusterItemContent = { item -> PinContent(item, icons) },
        )

        state.annotations.forEach { annotation ->
            when (annotation) {
                is AnnotationValue.LocalCluster ->
                    key("l:${annotation.value.id}") {
                        LocalClusterMarker(annotation.value, maxRange, icons)
                    }

                is AnnotationValue.Cluster ->
                    key("c:${annotation.value.preview.cid}") {
                        ServerClusterMarker(
                            annotation.value,
                            maxRange,
                            icons,
                            imageLoader,
                            thumbnailTargetPx,
                        )
                    }

                is AnnotationValue.Image -> Unit // handled by Clustering above
            }
        }
    }
}

/** A loose image pin (rotated tinted teardrop) as Compose cluster-item content. */
@Composable
private fun PinContent(
    item: ImageClusterItem,
    icons: AnnotationIconFactory,
) {
    val bitmap =
        remember(item.tint, item.foreground, item.angleDeg) {
            icons.pinBitmap(item.tint, item.foreground, item.angleDeg)
        }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = item.title)
}

/** A count bubble (tinted capsule) for an overlap cluster; tint from the first member's year. */
@Composable
private fun BubbleContent(
    cluster: Cluster<ImageClusterItem>,
    icons: AnnotationIconFactory,
) {
    val first = cluster.items.first()
    val bitmap =
        remember(first.tint, first.foreground, cluster.size) {
            icons.bubbleBitmap(first.tint, first.foreground, cluster.size)
        }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
}

/** A grid local cluster (≥5 in a cell): a tinted count capsule marker. */
@Composable
@GoogleMapComposable
private fun LocalClusterMarker(
    cluster: ModelLocalCluster,
    maxRange: IntRange,
    icons: AnnotationIconFactory,
) {
    val tint =
        CurrentScheme.color(
            cluster.images
                .first()
                .date.year,
            maxRange,
        )
    val descriptor =
        remember(tint, cluster.images.size) {
            BitmapDescriptorFactory.fromBitmap(
                icons.bubbleBitmap(tint, CurrentScheme.foreground(tint), cluster.images.size),
            )
        }
    Marker(
        state = rememberUpdatedMarkerState(position = cluster.coordinate.toLatLng()),
        icon = descriptor,
        anchor = CenterAnchor,
    )
}

/** A server cluster: a Coil thumbnail (loaded off-thread) that snaps in over a tinted placeholder. */
@Composable
@GoogleMapComposable
private fun ServerClusterMarker(
    cluster: ModelCluster,
    maxRange: IntRange,
    icons: AnnotationIconFactory,
    imageLoader: ImageLoader,
    thumbnailTargetPx: Int,
) {
    val tint = CurrentScheme.color(cluster.preview.date.year, maxRange)
    val foreground = CurrentScheme.foreground(tint)
    val thumbnail = rememberThumbnail(imageLoader, cluster.preview.imagePath, thumbnailTargetPx)
    val descriptor =
        remember(tint, foreground, cluster.count, thumbnail) {
            BitmapDescriptorFactory.fromBitmap(
                icons.serverClusterBitmap(thumbnail, tint, foreground, cluster.count),
            )
        }
    Marker(
        state = rememberUpdatedMarkerState(position = cluster.coordinate.toLatLng()),
        icon = descriptor,
        anchor = CenterAnchor,
    )
}

/**
 * Loads a cluster preview at low (`s`) quality via Coil, decoded down to [targetPx] (it's only
 * shown at 60dp); `null` until it arrives (or on failure). The decode + software copy run off the
 * main thread so a burst of cluster loads doesn't stall panning.
 */
@Composable
private fun rememberThumbnail(
    imageLoader: ImageLoader,
    path: String,
    targetPx: Int,
): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, path, targetPx) {
        val url = imageUrl(path, ImageQuality.Low)
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(
                                context,
                            ).data(url)
                            .size(Size(targetPx, targetPx))
                            .build()
                    // The thumbnail is composited onto a software Canvas, which can't draw a
                    // hardware bitmap — force a software copy if Coil handed one back.
                    (
                        imageLoader.execute(
                            request,
                        ) as? SuccessResult
                    )?.image?.toBitmap()?.toSoftware()
                }.getOrNull()
            }
    }
    return bitmap
}

/** A software-backed copy if this is a hardware bitmap (undrawable on a software Canvas), else itself. */
private fun Bitmap.toSoftware(): Bitmap =
    if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
