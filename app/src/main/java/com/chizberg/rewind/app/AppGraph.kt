package com.chizberg.rewind.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.chizberg.rewind.BuildConfig
import com.chizberg.rewind.R
import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.core.util.OrientationLock
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.domain.ModelImageDetails
import com.chizberg.rewind.features.comparison.ComparisonRenderer
import com.chizberg.rewind.features.comparison.ComparisonState
import com.chizberg.rewind.features.comparison.ComparisonViewDeps
import com.chizberg.rewind.features.comparison.makeComparisonModel
import com.chizberg.rewind.features.details.ComparisonFactory
import com.chizberg.rewind.features.details.LanguageDetector
import com.chizberg.rewind.features.details.makeImageDetailsModel
import com.chizberg.rewind.features.favorites.FavoritesModel
import com.chizberg.rewind.features.favorites.isFavorite
import com.chizberg.rewind.features.favorites.makeFavoritesModel
import com.chizberg.rewind.features.map.CameraFocus
import com.chizberg.rewind.features.map.LocationModel
import com.chizberg.rewind.features.map.MapAction
import com.chizberg.rewind.features.map.MapState
import com.chizberg.rewind.features.map.makeLocationModel
import com.chizberg.rewind.features.map.makeMapModel
import com.chizberg.rewind.features.onboarding.OnboardingModel
import com.chizberg.rewind.features.onboarding.OnboardingStorage
import com.chizberg.rewind.features.onboarding.makeOnboardingViewModel
import com.chizberg.rewind.features.search.makeSearchModel
import com.chizberg.rewind.features.settings.SettingsState
import com.chizberg.rewind.features.settings.makeSettingsViewModel
import com.chizberg.rewind.network.RequestPerformer
import com.chizberg.rewind.network.RewindRemotes
import com.chizberg.rewind.network.invoke
import com.chizberg.rewind.network.okHttpRequestPerformer
import com.chizberg.rewind.persistence.FavoritesStorage
import com.chizberg.rewind.persistence.JsonPreference
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import java.io.File

/** Zoom the map settles at when jumping to an image from its details screen (iOS `focusOn` z17). */
private const val SHOW_ON_MAP_ZOOM = 17f

/** Zoom the map settles at on a found place (iOS `AppGraph`'s `focusOn(..., zoom: 15)`) — wider
 *  than [SHOW_ON_MAP_ZOOM], since a place is a neighbourhood, not a single photo. */
private const val SEARCH_FOCUS_ZOOM = 15f

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
            // Two concurrent decodes instead of Coil's unbounded Default-dispatcher use. Preview
            // decodes are 17–34ms each and arrive in bursts exactly when a wave of annotations is
            // composing; traced on-device (M16 perf pass), the burst kept every big core busy
            // (~120ms of DefaultDispatcher work across one frame) while the main thread sat
            // runnable behind it. Capping decodes at two leaves a big core free for the frame; the
            // decodes themselves just queue a little longer — invisible next to network latency.
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(2))
            .build()

    private val remotes =
        RewindRemotes(
            RequestPerformer(
                okHttpRequestPerformer(
                    OkHttpClient
                        .Builder()
                        // Only translation needs it; see the interceptor for why the other Google
                        // endpoint doesn't.
                        .addInterceptor(AndroidClientInterceptor(appContext))
                        .build(),
                ),
            ),
        )

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

    // The "Go to Settings" button of the denied-location alert. iOS opens
    // `UIApplication.openSettingsURLString` through its `urlOpener`; Android has no URL for it, so
    // this is a system intent rather than one more link (and so it bypasses [canOpenUrl] entirely).
    private val openAppSettings: () -> Unit = {
        runCatching {
            appContext.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${appContext.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
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

    /** A single persisted counter, iOS `storage.makeCodableField(key:default: 0)`. */
    private fun intPreference(key: String): Property<Int> {
        val preference =
            JsonPreference(
                dataStore = dataStore,
                key = key,
                serializer = Int.serializer(),
                defaultValue = 0,
                scope = scope,
            )
        return Property(getter = { preference.value }, setter = { preference.value = it })
    }

    private val favoritesStorage = FavoritesStorage(dataStore, scope)

    /** The single favorites reducer, shared by every image-details screen (the star) and the app
     * reducer (the Favorites list). Persists through [favoritesStorage]'s synchronous Property. */
    val favoritesModel: FavoritesModel = makeFavoritesModel(favoritesStorage.property, scope)

    // Persisted user settings, the same synchronous-cache JsonPreference as favorites, under the
    // "settings" key (iOS's own key). The whole SettingsState travels as one blob, so a relaunch
    // brings back the sort order, the cluster-preview switch and the tint scheme together.
    private val settingsStorage =
        JsonPreference(
            dataStore = dataStore,
            key = "settings",
            serializer = SettingsState.serializer(),
            defaultValue = SettingsState(),
            scope = scope,
        )

    // Port of iOS `makeSettings(storage:)` -> `ObservableProperty<SettingsState>`: ONE observable
    // holder in front of the persisted blob, which everything else derives from. Whoever writes it
    // — a list's sort menu, the settings screen writing the whole blob back — feeds the same flow,
    // so the two subscriptions in `init` (iOS's two `onChange`s) see every change regardless of
    // source.
    private val settingsFlow = MutableStateFlow(settingsStorage.value)

    private val settings: Property<SettingsState> =
        Property(
            getter = { settingsFlow.value },
            setter = { newSettings ->
                settingsFlow.value = newSettings
                settingsStorage.value = newSettings
            },
        )

    /** iOS reads `settings.value.openClusterPreviews` synchronously when a cluster is tapped; ours
     * is read at the same moment, only from the UI (a cluster tap picks its route in `RewindMap` —
     * the camera lives in Compose). One stable lambda, so passing it never churns recomposition. */
    val openClusterPreviews: () -> Boolean = { settings.value.openClusterPreviews }

    // The image-list sort order — iOS `settings.asVariable().map(\.sorting)`, kept as a Property so
    // the list reducer keeps the synchronous get/set it expects. Shared with the map so its
    // previews follow the same order.
    private val imageSorting: Property<ImageSorting> =
        Property(
            getter = { settings.value.sorting },
            setter = { newSorting -> settings.value = settings.value.copy(sorting = newSorting) },
        )

    // The two review counters, each under iOS's own UserDefaults key and each its own entry (iOS
    // keeps two `makeCodableField`s too, rather than one blob like settings/onboarding).
    private val launchCount = intPreference("launchCount")
    private val requestCount = intPreference("requestCount")

    private val reviewPromptRequestsMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Rings when the counters say it is time to ask; [ReviewPromptHost] answers it. */
    val reviewPromptRequests: SharedFlow<Unit> = reviewPromptRequestsMutable

    private val reviewPrompter =
        ReviewPrompter(
            launchCount = launchCount,
            requestCount = requestCount,
            // Play needs an Activity — see ReviewPromptHost, and M13.5's location permission for
            // the same shape.
            showPrompt = { reviewPromptRequestsMutable.tryEmit(Unit) },
        )

    /** Plays the taps iOS fires inline from its reducers; [HapticsHost] lends it a view. */
    val haptics = AndroidHaptics()

    // The first-run flag, its own blob under iOS's "onboarding" key (not a field of SettingsState).
    private val onboardingStorage =
        JsonPreference(
            dataStore = dataStore,
            key = "onboarding",
            serializer = OnboardingStorage.serializer(),
            defaultValue = OnboardingStorage(),
            scope = scope,
        )

    // One location source and one reducer per graph, matching iOS's single `makeLocationModel()`:
    // tracking outlives any single screen. The source is the Play Services boundary; the reducer
    // itself is JVM-only (see features/map/LocationModel.kt).
    private val locationSource = FusedLocationSource(appContext, scope)

    /** Rings when the map asks for location access; [LocationPermissionHost] answers it. */
    val locationPermissionRequests: SharedFlow<Unit> = locationSource.permissionRequests

    val locationModel: LocationModel = makeLocationModel(locationSource, scope)

    val mapModel: Reducer<MapState, MapAction> =
        makeMapModel(
            annotationsRemote = remotes.annotations,
            onLoadFailed = {
                appModel(AppAction.Alert.Present(nonCancelledError("Unable to load images", it)))
            },
            scope = scope,
            sorting = { imageSorting.value },
            // The reducer decides where and how far to fly; the root view flies there (M9's
            // divergence — the camera lives in Compose).
            focusCamera = { focusRequestsMutable.tryEmit(it) },
            locationModel = { locationModel(it) },
            presentAlert = { appModel(AppAction.Alert.Present(it)) },
            openAppSettings = openAppSettings,
        )

    // What screen, if any, is currently holding the device in one orientation. Port of iOS's
    // `AppGraph.orientationLock` property, which its AppDelegate answers
    // `supportedInterfaceOrientationsFor` out of; here [OrientationLockHost] collects it in the
    // root view and sets `requestedOrientation`.
    private val orientationLockMutable = MutableStateFlow<OrientationLock?>(null)

    val orientationLock: StateFlow<OrientationLock?> = orientationLockMutable

    /**
     * One comparison screen, built the moment its button is tapped. Port of the
     * `makeComparisonViewDeps` call iOS makes inline inside its details reducer.
     *
     * Everything here is per-presentation, as on iOS: a camera session (Street View mode gets
     * none — the panorama is a view, not a session), the renderer the screen registers its
     * snapshots with, and a fresh orientation sensor subscription. The availability remote is bound
     * to this photo's coordinate right here, mirroring iOS's `.mapArgs { modelImage.coordinate }`.
     */
    private val comparisonFactory: ComparisonFactory = { image, mode ->
        val renderer = ComparisonRenderer()
        val cameraSession =
            when (mode) {
                ComparisonState.CaptureMode.Camera -> CameraXSession(appContext)
                ComparisonState.CaptureMode.StreetView -> null
            }
        // The screen's own scope: same main dispatcher as every other reducer, but a job of its
        // own, so closing the screen ends the orientation-sensor subscription that
        // `Reducer.adding` starts (on iOS the tracker simply dies with the model).
        val presentationScope =
            CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        ComparisonViewDeps(
            model =
                makeComparisonModel(
                    captureMode = mode,
                    oldImage = image,
                    streetViewAvailability =
                        remotes.streetViewAvailability.mapArgs { _: Unit -> image.coordinate },
                    cameraSession = cameraSession,
                    renderer = renderer,
                    orientation = deviceOrientation(appContext),
                    haptics = haptics,
                    saveImage = { captured ->
                        imageExport.save(captured.bitmap(), comparisonFileName(image.cid))
                    },
                    shareImage = { captured, title, url ->
                        imageExport.share(
                            bitmap = captured.bitmap(),
                            fileName = comparisonFileName(image.cid),
                            title = title,
                            // iOS joins the same two items into the text it shares beside the
                            // image (`makeShareVC`: title, no description, url).
                            text =
                                listOf(
                                    title,
                                    url,
                                ).filter { it.isNotBlank() }.joinToString("\n\n"),
                        )
                    },
                    scope = presentationScope,
                ),
            cameraSession = cameraSession,
            renderer = renderer,
            onClose = { presentationScope.cancel() },
        )
    }

    // One detector per graph — it holds no per-screen state, and ML Kit only wakes up on the first
    // description that needs classifying (see MlKitLanguageDetector).
    private val languageDetector: LanguageDetector = MlKitLanguageDetector()

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
            makeComparison = comparisonFactory,
            setOrientationLock = { orientationLockMutable.value = it },
            translate = remotes.translate,
            detectLanguage = languageDetector,
            haptics = haptics,
            // iOS reads `Bundle.main.preferredLocalizations.first` — the localization the *bundle*
            // picked, already matched against the ones it ships. `Locale.getDefault().language` is
            // not that: on a Japanese phone it answers "ja" while every string on screen is the
            // English fallback, so a Japanese description would look "already translated". The
            // string below is the language code of whichever `values-*` folder Android actually
            // resolved — the same question iOS asks, answered by the same resource machinery.
            // Read per screen, so a change in the system's per-app language picker takes hold on
            // the next one (the graph outlives the activity that recreation rebuilds).
            appLanguage = appContext.getString(R.string.app_language),
            extractModelImage = ::extractModelImage,
            scope = scope,
        )
    }

    // Places is initialised on the first search, not at startup: the SDK is only ever needed by that
    // one screen, and the map (a different SDK, same key) must not wait on it. `isInitialized` guards
    // the process-wide singleton against a second call after an activity recreation.
    private val placesClient: PlacesClient by lazy {
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(appContext, BuildConfig.MAPS_API_KEY)
        }
        Places.createClient(appContext)
    }

    private val searchModelFactory: SearchModelFactory = {
        makeSearchModel(
            // A provider per screen, as on iOS — it owns the Places billing session, which must not
            // outlive the search it belongs to (see GooglePlacesSuggestProvider).
            suggestProvider = GooglePlacesSuggestProvider(placesClient),
            onLocationFound = { coordinate ->
                // iOS: `appModelRef?(.search(.dismiss))` then `focusOn(coordinate, zoom: 15)`.
                appModel(AppAction.Search.Dismiss)
                focusRequestsMutable.tryEmit(CameraFocus(coordinate, SEARCH_FOCUS_ZOOM))
            },
            scope = scope,
        )
    }

    private val settingsModelFactory: SettingsModelFactory = {
        makeSettingsViewModel(
            // The same holder every other reader derives from, so a change made here reaches the
            // map (tint scheme, cluster previews) through `init`'s subscriptions.
            settings = settings,
            urlOpener = urlOpener,
            haptics = haptics,
            scope = scope,
        )
    }

    /**
     * The first-run wizard, or null once it has been seen — iOS `makeOnboardingViewModel` returning
     * nil is the gate, and the app state simply seeds itself from it.
     */
    private val onboardingModel: OnboardingModel? =
        makeOnboardingViewModel(
            storage =
                Property(
                    getter = { onboardingStorage.value },
                    setter = { onboardingStorage.value = it },
                ),
            onFinish = {
                // 🩼 as on iOS: while the onboarding is up the map deliberately never sends
                // `mapViewLoaded` (see RootView), so nothing has asked for location access yet —
                // finishing has to send it by hand.
                mapModel(MapAction.External.Ui.MapViewLoaded)
                appModel(AppAction.Onboarding.Dismiss)
            },
            scope = scope,
        )

    val appModel: AppModel =
        makeAppModel(
            imageDetailsFactory = imageDetailsFactory,
            searchModelFactory = searchModelFactory,
            settingsModelFactory = settingsModelFactory,
            favoritesModel = favoritesModel,
            onboardingModel = onboardingModel,
            currentRegionImages = { mapModel.state.value.currentRegionImages },
            sorting = imageSorting,
            requestReview = { reviewPrompter.request() },
            initialGradientScheme = settings.value.gradientScheme,
            scope = scope,
        )

    init {
        // Port of iOS `AppGraph.init`'s last line, and it lands in the same place for the same
        // reason: the graph is built once per process. `RewindViewModel` holds it across activity
        // recreation, so a rotation — or any other configuration change — is not a launch. (An
        // activity that genuinely finishes would clear the ViewModel and count one more launch, but
        // back on the map minimises the task instead of finishing; see MainActivity.)
        reviewPrompter.appLaunched()
        // Port of iOS `AppGraph`: `settings.sorting.onChange { mapModel(.internal(.updatePreviews)) }`.
        // A sort change made in a list's menu writes [imageSorting]; the map shares that order, so
        // its previews re-sort immediately instead of waiting for the next region change. `drop(1)`
        // skips the initial value — the map sorts on its own first load anyway.
        scope.launch {
            settingsFlow
                .map { it.sorting }
                .distinctUntilChanged()
                .drop(1)
                .collect { mapModel(MapAction.Internal.UpdatePreviews) }
        }
        // Port of iOS `settings.gradientScheme.onChange { appModel(.setGradientScheme($0)) }`: the
        // scheme is picked in Settings but lives in the app state, whence every tinted surface
        // reads it — so pins, badges and the year selector repaint the moment it changes.
        scope.launch {
            settingsFlow
                .map { it.gradientScheme }
                .distinctUntilChanged()
                .drop(1)
                .collect { appModel(AppAction.SetGradientScheme(it)) }
        }
        // Port of iOS `AppGraph`: `locationModel.$state.currentAndNewValues.addObserver { ... }`.
        // The current value comes with the subscription (a StateFlow replays it), which seeds the
        // map's own `locationState` — iOS seeds it through `MapState.makeInitial(locationState:)`
        // and then gets the same value once more from the observer. Both are the empty state, and
        // `newLocationState` no-ops on a fix-less update, so the duplicate is harmless either way.
        locationModel.onStateUpdate { mapModel(MapAction.External.NewLocationState(it)) }
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
