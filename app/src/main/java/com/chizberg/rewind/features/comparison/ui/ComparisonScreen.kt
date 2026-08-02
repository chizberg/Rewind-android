package com.chizberg.rewind.features.comparison.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.R
import com.chizberg.rewind.app.CameraPermissionHost
import com.chizberg.rewind.app.RewindAlert
import com.chizberg.rewind.features.comparison.ComparisonAction
import com.chizberg.rewind.features.comparison.ComparisonState
import com.chizberg.rewind.features.comparison.ComparisonViewDeps
import com.chizberg.rewind.features.comparison.Orientation
import com.chizberg.rewind.features.comparison.year
import com.chizberg.rewind.ui.theme.RewindTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration.Companion.seconds

/** iOS `Color.clear.aspectRatio(4 / 6)`: two 4:3 frames stacked. */
private const val CANVAS_ASPECT = 4f / 6f

/** iOS `shutterButtonSize` and its inner ring inset. */
private val ShutterSize = 80.dp
private val ShutterInset = 6.dp

/** iOS `.padding(.horizontal, 35)` around the back / share pair, and the gaps above them —
 *  `pickers.padding(.bottom, 20)` and `bottomControls.padding(.bottom, 75)`, both measured from
 *  inside the safe area. */
private val ControlsInset = 35.dp
private val PickersGap = 20.dp
private val ControlsGap = 75.dp

private val OverlayButtonSize = 48.dp

/** How long "Saved to Photos" stays up — iOS's `.pause` phase between the two slides. */
private val BannerDuration = 2.seconds

/**
 * The then/now comparison screen. Port of iOS `ComparisonScreen`: the canvas pinned to the top, the
 * style (and lens) pickers plus the shutter floating over the bottom, a "saved" banner sliding in
 * from the top, and the alerts of a screen that can fail in six different ways.
 *
 * The scene is forced dark, as on iOS (`.environment(\.colorScheme, .dark)`) — a viewfinder sits
 * better in one, and the composite is framed against black either way. Nothing else about the app's
 * theming changes: this is a nested [RewindTheme], not an activity-level switch.
 *
 * [onDismiss] is the parent's (the details screen's) close, used both by the back button and by the
 * reducer's own `shouldDismiss` — the child cannot close itself, so it raises a flag and this
 * forwards it, which is exactly the iOS shape (`.onChange(of: store.shouldDismiss) { dismiss() }`).
 */
@Composable
fun ComparisonScreen(
    deps: ComparisonViewDeps,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = deps.model
    val state by model.state.collectAsStateWithLifecycle()

    // Camera access: the reducer asks, this answers (see CameraPermissionHost). Composed BEFORE
    // the `viewWillAppear` below, because that is the ask — effects start in composition order,
    // and a request rung into a flow nobody is collecting yet is simply lost.
    if (state.captureMode == ComparisonState.CaptureMode.Camera) {
        val requests = remember(deps) { deps.cameraSession?.permissionRequests ?: emptyFlow() }
        CameraPermissionHost(
            requests = requests,
            onAccessChanged = { granted ->
                model(
                    if (granted) {
                        ComparisonAction.Internal.VideoAccessGranted
                    } else {
                        ComparisonAction.External.Alert.PresentAccessError
                    },
                )
            },
        )
    }

    // iOS `.task { store(.viewWillAppear) }`.
    LaunchedEffect(model) { model(ComparisonAction.External.ViewWillAppear) }

    LaunchedEffect(state.shouldDismiss) { if (state.shouldDismiss) onDismiss() }

    // iOS fires this inside the reducer's `.shoot`; there is no haptics facade in the port yet
    // (M16), so the confirmation is played here, off the same counter that drives the blink.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.shotsCount) {
        if (state.shotsCount > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    // Android-only, see PanoramaZoom: a panorama opens at its widest and there is no iOS control to
    // port. Lives here rather than in the reducer for the same reason the map's cluster zoom does —
    // the camera it moves belongs to the view, not to the state.
    val panoramaZoom = remember { PanoramaZoom() }

    // iOS `rotating(with:)`: the screen stays portrait, so every glyph on it turns back by hand.
    val rotation by
        animateFloatAsState(state.orientation.rotationAngle, label = "controlRotation")

    RewindTheme(darkTheme = true) {
        // A `Surface`, not a `Box` with a background: it is what puts `onSurface` into
        // `LocalContentColor`, and every unstyled label on the canvas (the years, the chevrons)
        // takes its colour from there — the bare default is black, which in this forced-dark scene
        // is black on black.
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.fillMaxSize()) {
                ComparisonCanvas(deps, state, panoramaZoom, rotation)

                SavedBanner(
                    savesCount = state.savesCount,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(top = 5.dp),
                )

                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .padding(bottom = ControlsGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PickersGap),
                ) {
                    Pickers(state, model::invoke, rotation)
                    BottomControls(state, model::invoke, onDismiss)
                }
            }
        }

        state.alert?.let { params ->
            RewindAlert(
                params = params,
                onDismiss = { model(ComparisonAction.External.Alert.Dismiss) },
            )
        }
    }
}

/**
 * The composite itself, sized 4:6 and pinned to the top (iOS `VStack { canvas; Spacer() }`), with
 * the recorder that turns it into the saved / shared image wrapped around it.
 *
 * The width is capped so the canvas always fits the height it has: the screen is portrait-locked
 * while it is up, but a tablet — or the frame or two before the lock lands — must not push the
 * bottom of the photo off screen.
 */
@Composable
private fun ComparisonCanvas(
    deps: ComparisonViewDeps,
    state: ComparisonState,
    panoramaZoom: PanoramaZoom,
    rotation: Float,
) {
    val recorder =
        canvasRecorder(
            renderer = deps.renderer,
            enabled = state.captureState is ComparisonState.CaptureState.Taken,
        )
    // Inside the safe area, as iOS's `VStack { canvas; Spacer() }` is — only the black behind it
    // (`Color.systemBackground.ignoresSafeArea()`, our `Surface`) runs under the status bar.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val canvasWidth = minOf(maxWidth, maxHeight * CANVAS_ASPECT)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(canvasWidth)
                .aspectRatio(CANVAS_ASPECT),
        ) {
            ComparisonView(
                style = state.style,
                oldImage = state.oldImage,
                captureState = state.captureState,
                streetViewYear = state.streetViewAvailability.year,
                shotsCount = state.shotsCount,
                viewfinder = { viewfinderModifier ->
                    when (state.captureMode) {
                        ComparisonState.CaptureMode.Camera ->
                            CameraViewfinder(deps.cameraSession, viewfinderModifier)

                        ComparisonState.CaptureMode.StreetView ->
                            StreetViewViewfinder(
                                image = state.oldImage,
                                renderer = deps.renderer,
                                zoom = panoramaZoom,
                                modifier = viewfinderModifier,
                            )
                    }
                },
                recorder = recorder,
                modifier = Modifier.fillMaxSize(),
            )

            if (state.showsPanoramaZoom) {
                PanoramaZoomControl(
                    zoom = panoramaZoom,
                    rotation = rotation,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(ZoomInset),
                )
            }
        }
    }
}

/** How far the zoom stack floats off the canvas's corner. Google's own attribution sits along the
 *  panorama's bottom edge, so the top one is the free corner. */
private val ZoomInset = 12.dp

/** iOS `pickers`: the style control always, the lens control only while a camera is live. The
 *  panorama's zoom is the other viewfinder's equivalent, and it lives on the canvas instead — see
 *  [PanoramaZoomControl]. */
@Composable
private fun Pickers(
    state: ComparisonState,
    dispatch: (ComparisonAction) -> Unit,
    rotation: Float,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentedControl(
            items = ComparisonState.Style.entries,
            selected = state.style,
            onSelect = { dispatch(ComparisonAction.External.SetStyle(it)) },
        ) { style, _ ->
            Icon(
                imageVector = style.icon,
                contentDescription = stringResource(style.label),
                modifier = Modifier.rotate(rotation),
            )
        }

        val lens = state.currentLens
        if (lens != null && state.showsLensPicker) {
            SegmentedControl(
                items = state.availableLens,
                selected = lens,
                onSelect = { dispatch(ComparisonAction.External.SetLens(it)) },
            ) { item, _ ->
                Text(
                    text = item.title,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
}

/**
 * The panorama's zoom, floating over the viewfinder's trailing corner — the mirror of
 * [showsLensPicker]'s slot for the other viewfinder, moved next to what it changes.
 *
 * It is a **sibling** of the canvas, not a child: children are what the recorder records, and the
 * saved composite must not come out with a pair of buttons stamped into the panorama.
 */
@Composable
private fun PanoramaZoomControl(
    zoom: PanoramaZoom,
    rotation: Float,
    modifier: Modifier = Modifier,
) {
    StepperControl(
        modifier = modifier,
        steps =
            listOf(
                StepperStep(
                    icon = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.comparison_zoom_in),
                    enabled = zoom.canZoomIn,
                    onClick = { zoom.step(PanoramaZoom.ZOOM_STEP) },
                ),
                StepperStep(
                    icon = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.comparison_zoom_out),
                    enabled = zoom.canZoomOut,
                    onClick = { zoom.step(-PanoramaZoom.ZOOM_STEP) },
                ),
            ),
        glyphModifier = Modifier.rotate(rotation),
    )
}

/** The mirror of [showsLensPicker] for the other viewfinder: a live Google panorama, nothing
 *  else — the camera has its lenses, and a frozen shot has nothing left to zoom. */
private val ComparisonState.showsPanoramaZoom: Boolean
    get() =
        captureMode == ComparisonState.CaptureMode.StreetView &&
            captureState is ComparisonState.CaptureState.Viewfinder

/**
 * iOS: `currentLens != nil && captureMode == .camera && captureState.isViewfinder &&
 * availableLens.count > 1` — a live camera with more than one stop to offer. The `> 1` is the
 * point: a single-lens device gets no picker at all rather than a picker it cannot use.
 */
private val ComparisonState.showsLensPicker: Boolean
    get() =
        captureMode == ComparisonState.CaptureMode.Camera &&
            captureState is ComparisonState.CaptureState.Viewfinder &&
            availableLens.size > 1

/** iOS `bottomControls`: back and (once there is a shot) share at the edges, shutter between
 *  them. */
@Composable
private fun BottomControls(
    state: ComparisonState,
    dispatch: (ComparisonAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val taken = state.captureState is ComparisonState.CaptureState.Taken
    // Centred, because iOS's `ZStack` centres: the row of 48dp buttons is shorter than the 80dp
    // shutter it sits beside, and Compose's default `TopStart` would leave the two off by the
    // 16dp difference in their halves.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ControlsInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onDismiss,
            )
            if (taken) {
                OverlayIconButton(
                    icon = Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.action_share),
                    onClick = { dispatch(ComparisonAction.External.Share) },
                )
            } else {
                Spacer(Modifier.size(OverlayButtonSize))
            }
        }
        ShutterButton(
            retake = taken,
            onClick = {
                dispatch(
                    if (taken) {
                        ComparisonAction.External.Retake
                    } else {
                        ComparisonAction.External.Shoot
                    },
                )
            },
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * iOS `makeShutterButton`: a glass ring around a filled circle, which grows a refresh glyph once
 * there is a shot to redo. The ring is the tonal stand-in for the blur (design canon: floating
 * chrome separates tonally in a dark scene, not by shadow).
 */
@Composable
private fun ShutterButton(
    retake: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(ShutterSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(ShutterInset)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            if (retake) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.comparison_retake),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** iOS `OverlayButton`: a glass circle over the canvas. */
@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(OverlayButtonSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

/**
 * iOS `SavedBanner`: a capsule that slides down from the top on every save, waits, and slides back
 * out. Keyed on the save counter, so a second save re-announces itself.
 *
 * Deliberately its own host rather than the app's (future) snackbar: this one belongs to the
 * screen, and M3's bottom snackbar would land under the shutter.
 */
@Composable
private fun SavedBanner(
    savesCount: Int,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(savesCount) {
        if (savesCount == 0) return@LaunchedEffect
        visible = true
        delay(BannerDuration)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.DownloadDone, contentDescription = null)
                Text(stringResource(R.string.comparison_saved_to_photos))
            }
        }
    }
}

/** iOS `ComparisonState.Style.iconName`: `rectangle.split.1x2` / `rectangle.on.rectangle`. */
private val ComparisonState.Style.icon: ImageVector
    get() =
        when (this) {
            ComparisonState.Style.SideBySide -> Icons.Rounded.Splitscreen
            ComparisonState.Style.CardOnCard -> Icons.Rounded.FilterNone
        }

private val ComparisonState.Style.label: Int
    get() =
        when (this) {
            ComparisonState.Style.SideBySide -> R.string.comparison_style_side_by_side
            ComparisonState.Style.CardOnCard -> R.string.comparison_style_card_on_card
        }

/**
 * iOS `Orientation.rotationAngle`: how far the glyphs turn back so they stay upright while the
 * screen itself does not rotate.
 */
private val Orientation.rotationAngle: Float
    get() =
        when (this) {
            Orientation.Portrait -> 0f
            Orientation.LandscapeLeft -> 90f
            Orientation.LandscapeRight -> -90f
            Orientation.UpsideDown -> 180f
        }
