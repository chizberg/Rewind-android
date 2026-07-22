package com.chizberg.rewind.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.features.details.makeImageDetailsModel
import com.chizberg.rewind.features.favorites.FavoritesModel
import com.chizberg.rewind.features.favorites.isFavorite
import com.chizberg.rewind.features.favorites.makeFavoritesModel
import com.chizberg.rewind.features.map.CameraFocus
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.MapState
import com.chizberg.rewind.features.map.makeMapModel
import com.chizberg.rewind.features.settings.SettingsState
import com.chizberg.rewind.network.RequestPerformer
import com.chizberg.rewind.network.RewindRemotes
import com.chizberg.rewind.network.invoke
import com.chizberg.rewind.network.okHttpRequestPerformer
import com.chizberg.rewind.persistence.FavoritesStorage
import com.chizberg.rewind.persistence.JsonPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

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

    /** Gallery / share sheet, both fed from [imageLoader]'s cache. */
    private val imageExport = ImageExport(appContext, imageLoader)

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

    // The favorites store lives on the DataStore, whose own actor runs on a background scope so the
    // JsonPreference constructor's priming `runBlocking` read (on the main scope) never deadlocks it
    // (see JsonPreference). Mirrors iOS UserDefaults: process-lived, synchronous at the boundary.
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storageScope) {
            File(appContext.filesDir, "datastore/rewind.preferences_pb").apply {
                parentFile?.mkdirs()
            }
        }

    private val favoritesStorage = FavoritesStorage(dataStore, scope)

    /** The single favorites reducer, shared by every image-details screen (the star) and the app
     * reducer (the Favorites list). Persists through [favoritesStorage]'s synchronous Property. */
    val favoritesModel: FavoritesModel = makeFavoritesModel(favoritesStorage.property, scope)

    // Persisted user settings (M10 stores only the sort order; the rest of SettingsState and its
    // editor land in M13). Same synchronous-cache JsonPreference as favorites, under the "settings"
    // key — so the sort order now survives a relaunch, matching iOS.
    private val settings =
        JsonPreference(
            dataStore = dataStore,
            key = "settings",
            serializer = SettingsState.serializer(),
            defaultValue = SettingsState(),
            scope = scope,
        )

    // The image-list sort order. Seeded from [settings] and mirrored back on every change. Held in a
    // StateFlow (not a plain var) so the map can react to a change the same way iOS does (see the
    // init subscription); the [Property] keeps the synchronous get/set the list reducer expects and
    // also writes through to [settings]. Shared with the map so its previews follow the same order.
    private val sortingFlow = MutableStateFlow(settings.value.sorting)
    private val imageSorting: Property<ImageSorting> =
        Property(
            getter = { sortingFlow.value },
            setter = { newSorting ->
                sortingFlow.value = newSorting
                settings.value = settings.value.copy(sorting = newSorting)
            },
        )

    val mapModel: Reducer<MapState, MapAction> =
        makeMapModel(
            annotationsRemote = remotes.annotations,
            onLoadFailed = {
                appModel(AppAction.Alert.Present(nonCancelledError("Unable to load images", it)))
            },
            scope = scope,
            sorting = { imageSorting.value },
        )

    private val imageDetailsFactory: ImageDetailsFactory = { image, source ->
        makeImageDetailsModel(
            modelImage = image,
            remote = remotes.imageDetails,
            openSource = source,
            // The star reads/writes the shared favorites reducer through iOS's `isFavorite(_:)`
            // bimap store (cid membership + a toggle lifted to add/remove). Recreating the store per
            // call is harmless — it holds no state of its own.
            isFavorite = { modelImage -> favoritesModel.isFavorite(modelImage).current },
            setFavorite = { modelImage, fav -> favoritesModel.isFavorite(modelImage)(fav) },
            showOnMap = { coordinate ->
                // Close whichever overlay this image sits under — details opened straight from a pin
                // (list is null, a no-op) or a grid list — before recentring, exactly like iOS.
                appModel(AppAction.ImageList.Dismiss)
                appModel(AppAction.ImageDetails.Dismiss)
                focusRequestsMutable.tryEmit(CameraFocus(coordinate, SHOW_ON_MAP_ZOOM))
            },
            canOpenUrl = canOpenUrl,
            urlOpener = urlOpener,
            saveImage = imageExport::save,
            shareImage = imageExport::share,
            extractModelImage = ::extractModelImage,
            scope = scope,
        )
    }

    val appModel: AppModel =
        makeAppModel(
            imageDetailsFactory = imageDetailsFactory,
            favoritesModel = favoritesModel,
            currentRegionImages = { mapModel.state.value.currentRegionImages },
            sorting = imageSorting,
            requestReview = {}, // Play In-App Review lands in M16.
            initialGradientScheme = GradientScheme.Rewind, // Settings-driven scheme lands in M13.
            scope = scope,
        )

    init {
        // Port of iOS `AppGraph`: `settings.sorting.onChange { mapModel(.internal(.updatePreviews)) }`.
        // A sort change made in a list's menu writes [imageSorting]; the map shares that order, so
        // its previews re-sort immediately instead of waiting for the next region change. `drop(1)`
        // skips the initial value — the map sorts on its own first load anyway.
        scope.launch {
            sortingFlow.drop(1).collect { mapModel(MapAction.Internal.UpdatePreviews) }
        }
    }
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
