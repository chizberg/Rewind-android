package com.chizberg.rewind.features.comparison.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.R
import com.chizberg.rewind.app.CameraPermissionHost
import com.chizberg.rewind.app.RewindAlert
import com.chizberg.rewind.features.comparison.ComparisonAction
import com.chizberg.rewind.features.comparison.ComparisonState
import com.chizberg.rewind.features.comparison.ComparisonViewDeps
import com.chizberg.rewind.features.comparison.year
import com.chizberg.rewind.ui.SavedBanner
import com.chizberg.rewind.ui.theme.RewindTheme
import kotlinx.coroutines.flow.emptyFlow

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

    // Android-only, see PanoramaZoom: a panorama opens at its widest and there is no iOS control to
    // port. Lives here rather than in the reducer for the same reason the map's cluster zoom does —
    // the camera it moves belongs to the view, not to the state.
    val panoramaZoom = remember { PanoramaZoom() }

    // The panorama the user has turned to face the scene outlives the activity a rotation rebuilds
    // (see PanoramaMemory). Hoisted to the screen so a retake comes back to it as well.
    val panoramaMemory = rememberSaveable(saver = PanoramaMemory.Saver) { PanoramaMemory() }

    RewindTheme(darkTheme = true) {
        // A `Surface`, not a `Box` with a background: it is what puts `onSurface` into
        // `LocalContentColor`, and every unstyled label on the canvas (the years, the chevrons)
        // takes its colour from there — the bare default is black, which in this forced-dark scene
        // is black on black.
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // iOS `viewSize` / `axis`: the controls go beside the canvas when the screen is wider
            // than the canvas is, below it otherwise — measured on the whole screen, safe area and
            // all, as iOS reads the size off its edge-to-edge background.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth / maxHeight > state.aspectRatio
                ComparisonCanvas(deps, state, model::invoke, panoramaZoom, panoramaMemory)

                SavedBanner(
                    savesCount = state.savesCount,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(top = 5.dp),
                )

                if (wide) {
                    Row(
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .safeDrawingPadding()
                                .padding(end = ControlsGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PickersGap),
                    ) {
                        Pickers(state, model::invoke, vertical = true)
                        BottomControls(state, model::invoke, onDismiss, vertical = true)
                    }
                } else {
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
                        Pickers(state, model::invoke, vertical = false)
                        BottomControls(state, model::invoke, onDismiss, vertical = false)
                    }
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
 * The composite itself, fitted to [ComparisonState.aspectRatio] and centred (iOS
 * `AxisStack { Spacer; canvas.aspectRatio(contentMode: .fit); Spacer }`), with the recorder that
 * turns it into the saved / shared image wrapped around it.
 */
@Composable
private fun ComparisonCanvas(
    deps: ComparisonViewDeps,
    state: ComparisonState,
    dispatch: (ComparisonAction) -> Unit,
    panoramaZoom: PanoramaZoom,
    panoramaMemory: PanoramaMemory,
) {
    val recorder =
        canvasRecorder(
            renderer = deps.renderer,
            enabled = state.captureState is ComparisonState.CaptureState.Taken,
        )
    // Inside the safe area, as iOS's stack is — only the black behind it
    // (`Color.systemBackground.ignoresSafeArea()`, our `Surface`) runs under the system bars.
    BoxWithConstraints(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val ratio = state.aspectRatio
        val canvasWidth = minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.size(canvasWidth, canvasWidth / ratio)) {
            ComparisonView(
                style = state.style,
                oldImage = state.oldImage,
                captureState = state.captureState,
                streetViewYear = state.streetViewAvailability.year,
                shotsCount = state.shotsCount,
                oldAspect = state.oldImageAspectRatio,
                onOldAspectLoaded = { dispatch(ComparisonAction.External.OldImageSizeLoaded(it)) },
                viewfinder = { viewfinderModifier ->
                    when (state.captureMode) {
                        ComparisonState.CaptureMode.Camera ->
                            CameraViewfinder(deps.cameraSession, viewfinderModifier)

                        ComparisonState.CaptureMode.StreetView ->
                            StreetViewViewfinder(
                                image = state.oldImage,
                                renderer = deps.renderer,
                                zoom = panoramaZoom,
                                memory = panoramaMemory,
                                modifier = viewfinderModifier,
                            )
                    }
                },
                recorder = recorder,
                modifier = Modifier.fillMaxSize(),
            )

            if (state.showsPanoramaZoom) {
                val corner = viewfinderTopEnd(state.style, canvasWidth)
                Box(Modifier.width(corner.x).padding(top = corner.y)) {
                    PanoramaZoomControl(
                        zoom = panoramaZoom,
                        modifier = Modifier.align(Alignment.TopEnd).padding(ZoomInset),
                    )
                }
            }
        }
    }
}

/** How far the zoom stack floats off the viewfinder's corner. Google's own attribution sits along
 *  the panorama's bottom edge, so the top one is the free corner. */
private val ZoomInset = 12.dp

/** iOS `pickers`: the style control always, the lens control only while a camera is live. The
 *  panorama's zoom is the other viewfinder's equivalent, and it lives on the canvas instead — see
 *  [PanoramaZoomControl]. Stacked across the controls' own axis, as iOS's `axis.perpendicular`. */
@Composable
private fun Pickers(
    state: ComparisonState,
    dispatch: (ComparisonAction) -> Unit,
    vertical: Boolean,
) {
    AxisStack(vertical = vertical, spacing = 8.dp) {
        SegmentedControl(
            vertical = vertical,
            items = ComparisonState.Style.entries,
            selected = state.style,
            onSelect = { dispatch(ComparisonAction.External.SetStyle(it)) },
        ) { style, _ ->
            Icon(
                imageVector = style.icon,
                contentDescription = stringResource(style.label),
            )
        }

        val lens = state.currentLens
        if (lens != null && state.showsLensPicker) {
            SegmentedControl(
                vertical = vertical,
                items = state.availableLens,
                selected = lens,
                onSelect = { dispatch(ComparisonAction.External.SetLens(it)) },
            ) { item, _ ->
                Text(
                    text = item.title,
                    fontFamily = FontFamily.Monospace,
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

/** iOS `bottomControls`: back and (once there is a shot) share at the ends, shutter between
 *  them — a row under the canvas, a column beside it. */
@Composable
private fun BottomControls(
    state: ComparisonState,
    dispatch: (ComparisonAction) -> Unit,
    onDismiss: () -> Unit,
    vertical: Boolean,
) {
    val taken = state.captureState is ComparisonState.CaptureState.Taken
    // Centred, because iOS's `ZStack` centres: the row of 48dp buttons is shorter than the 80dp
    // shutter it sits beside, and Compose's default `TopStart` would leave the two off by the
    // 16dp difference in their halves.
    Box(
        if (vertical) Modifier.fillMaxHeight() else Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        AxisStack(
            vertical = vertical,
            modifier =
                if (vertical) {
                    Modifier.fillMaxHeight().padding(vertical = ControlsInset)
                } else {
                    Modifier.fillMaxWidth().padding(horizontal = ControlsInset)
                },
            spaceBetween = true,
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
 * iOS `AxisStack`: a column or a row of the same children, centred across the other axis. With
 * [spaceBetween] the children are pushed to the ends (iOS puts a `Spacer()` between them).
 */
@Composable
private fun AxisStack(
    vertical: Boolean,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    spaceBetween: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (vertical) {
        Column(
            modifier = modifier,
            verticalArrangement =
                if (spaceBetween) Arrangement.SpaceBetween else Arrangement.spacedBy(spacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement =
                if (spaceBetween) Arrangement.SpaceBetween else Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}
