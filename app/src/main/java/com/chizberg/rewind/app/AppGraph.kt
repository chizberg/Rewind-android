package com.chizberg.rewind.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.features.details.makeImageDetailsModel
import com.chizberg.rewind.features.map.CameraFocus
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.MapState
import com.chizberg.rewind.features.map.makeMapModel
import com.chizberg.rewind.network.RequestPerformer
import com.chizberg.rewind.network.RewindRemotes
import com.chizberg.rewind.network.invoke
import com.chizberg.rewind.network.okHttpRequestPerformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient

/** Zoom the map settles at when jumping to an image from its details screen (iOS `focusOn` z17). */
private const val SHOW_ON_MAP_ZOOM = 17f

/**
 * The manual composition root. Port of iOS `AppGraph`: builds the network/image stack, the map and
 * app reducers, and the image-details factory, wiring their cross-references. Runs on a single main
 * [scope] (all reducers share it, mirroring iOS `@MainActor`).
 *
 * Cross-references that are cyclic on iOS (`mapModelRef`/`appModelRef` weak captures) are here plain
 * property reads inside lambdas that only fire after construction — [mapModel]'s failure alert and
 * the details factory's "show on map" both reach [appModel], declared below them.
 *
 * Divergence: the camera lives in Compose (`cameraPositionState`), not the reducer, so "show on map"
 * can't call a `mapModel(.focusOn)` that moves a `MKMapView`. Instead it emits onto [focusRequests];
 * the root view collects it and animates the camera. [android.content.Context] is allowed here — the
 * graph is the wiring layer, not pure logic.
 */
class AppGraph(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    /** One OkHttp-backed Coil loader shared by every image surface (icons, strip, details). */
    val imageLoader: ImageLoader =
        ImageLoader
            .Builder(appContext)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()

    private val remotes = RewindRemotes(RequestPerformer(okHttpRequestPerformer(OkHttpClient())))

    private val urlOpener: (String) -> Unit = { url ->
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private val canOpenUrl: (String) -> Boolean = { url ->
        Intent(Intent.ACTION_VIEW, url.toUri())
            .resolveActivity(appContext.packageManager) != null
    }

    private val focusRequestsMutable = MutableSharedFlow<CameraFocus>(extraBufferCapacity = 1)

    /** Camera-focus requests emitted by "show on map"; collected and animated by the root view. */
    val focusRequests: SharedFlow<CameraFocus> = focusRequestsMutable

    val mapModel: Reducer<MapState, MapAction> =
        makeMapModel(
            annotationsRemote = remotes.annotations,
            onLoadFailed = {
                appModel(AppAction.Alert.Present(nonCancelledError("Unable to load images", it)))
            },
            scope = scope,
        )

    private val imageDetailsFactory: ImageDetailsFactory = { image, source ->
        makeImageDetailsModel(
            modelImage = image,
            remote = remotes.imageDetails,
            openSource = source,
            // Real favorites gateway lands in M10; the star is off until then.
            isFavorite = { false },
            setFavorite = { _, _ -> },
            showOnMap = { coordinate ->
                appModel(AppAction.ImageDetails.Dismiss)
                focusRequestsMutable.tryEmit(CameraFocus(coordinate, SHOW_ON_MAP_ZOOM))
            },
            canOpenUrl = canOpenUrl,
            urlOpener = urlOpener,
            extractModelImage = ::extractModelImage,
            scope = scope,
        )
    }

    val appModel: AppModel =
        makeAppModel(
            imageDetailsFactory = imageDetailsFactory,
            requestReview = {}, // Play In-App Review lands in M16.
            initialGradientScheme = GradientScheme.Rewind, // Settings-driven scheme lands in M13.
            scope = scope,
        )
}

/** iOS `extractModelImage`: a details payload as a map [ModelImage] (path for Coil, not a loader). */
private fun extractModelImage(details: ModelImageDetails): ModelImage =
    ModelImage(
        cid = details.cid,
        imagePath = details.file,
        title = details.title,
        dir = details.dir,
        coordinate = details.coordinate,
        date = details.date,
    )
