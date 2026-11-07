package com.chizberg.rewind.features.map.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.R
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.imagelist.ui.EmptyList
import com.chizberg.rewind.features.imagelist.ui.EmptyListPlaceholder
import com.chizberg.rewind.features.imagelist.ui.ImageGrid
import com.chizberg.rewind.features.imagelist.ui.SortMenu
import com.chizberg.rewind.features.map.SHEET_MINIMIZED_HEIGHT
import com.chizberg.rewind.features.map.SHEET_NORMAL_HEIGHT
import com.chizberg.rewind.features.map.SheetDetent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionImagesSheet(
    detent: SheetDetent,
    images: List<ModelImage>,
    showsMapChrome: Boolean,
    isLoading: Boolean,
    sorting: ImageSorting,
    emptyResultCount: Int,
    placeholders: List<EmptyListPlaceholder>,
    @StringRes hint: Int?,
    scheme: GradientScheme,
    maxRange: IntRange,
    onStateChanged: (detent: SheetDetent, visibleHeight: Float, showsSheetToolbar: Boolean) -> Unit,
    onSetDetent: (SheetDetent) -> Unit,
    onImageClick: (ModelImage) -> Unit,
    onSortingChange: (ImageSorting) -> Unit,
    onFavoritesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val height = constraints.maxHeight.toFloat()
        val normalOffset =
            with(density) { height - SHEET_NORMAL_HEIGHT.dp.toPx() }.coerceAtLeast(1f)
        val anchors =
            remember(height, density) {
                DraggableAnchors {
                    SheetDetent.Minimized at
                        with(density) { height - SHEET_MINIMIZED_HEIGHT.dp.toPx() }
                    SheetDetent.Normal at normalOffset
                    SheetDetent.Fullscreen at 0f
                }
            }
        val sheet = remember { AnchoredDraggableState(initialValue = detent, anchors = anchors) }
        var current by remember { mutableStateOf(detent) }
        var pending by remember { mutableStateOf<SheetDetent?>(null) }
        var isDragging by remember { mutableStateOf(false) }
        var handled by remember { mutableStateOf(detent) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val flingThreshold = with(density) { FLING_THRESHOLD.dp.toPx() }
        val offset by remember(normalOffset) {
            derivedStateOf {
                sheet.offset.takeUnless { it.isNaN() }
                    ?: normalOffset
            }
        }
        val progress by remember(normalOffset) {
            derivedStateOf { (1 - offset / normalOffset).coerceIn(0f, 1f) }
        }

        LaunchedEffect(anchors) { sheet.updateAnchors(anchors, current) }

        fun settle(
            target: SheetDetent,
            velocity: Float,
        ) {
            pending = null
            current = target
            settleJob?.cancel()
            settleJob =
                scope.launch {
                    sheet.anchoredDrag(target) { latestAnchors, latest ->
                        val from = sheet.requireOffset()
                        val to = latestAnchors.positionOf(latest)
                        val limit = MAX_RELATIVE_VELOCITY * abs(to - from)
                        animate(
                            from,
                            to,
                            velocity.coerceIn(-limit, limit),
                            SettleSpring,
                        ) {
                            value,
                            _,
                            ->
                            dragTo(value.coerceIn(0f, latestAnchors.maxPosition()))
                        }
                    }
                }
        }

        fun startDrag() {
            if (isDragging) return
            settleJob?.cancel()
            isDragging = true
        }

        fun release(velocity: Float) {
            isDragging = false
            val position = sheet.requireOffset()
            val detents = SheetDetent.entries.sortedBy { anchors.positionOf(it) }
            val target =
                pending ?: when {
                    velocity > flingThreshold ->
                        detents.firstOrNull { anchors.positionOf(it) > position }
                            ?: SheetDetent.Minimized
                    velocity < -flingThreshold ->
                        detents.lastOrNull { anchors.positionOf(it) < position }
                            ?: SheetDetent.Fullscreen
                    else -> detents.minBy { abs(anchors.positionOf(it) - position) }
                }
            settle(target, velocity)
        }

        LaunchedEffect(detent) {
            handled = detent
            when {
                detent == current -> pending = null
                isDragging -> pending = detent
                else -> settle(detent, 0f)
            }
        }

        val reportState by rememberUpdatedState(onStateChanged)
        val requested by rememberUpdatedState(detent)
        LaunchedEffect(sheet, normalOffset) {
            snapshotFlow {
                sheet.offset
                    .takeUnless { it.isNaN() || requested != handled }
                    ?.let {
                        val visible = with(density) { (height - it).toDp().value }
                        Triple(
                            current,
                            minOf(visible, SHEET_NORMAL_HEIGHT),
                            if (isDragging) {
                                progress > TOOLBAR_PROGRESS
                            } else {
                                current == SheetDetent.Fullscreen
                            },
                        )
                    }
            }.filterNotNull()
                .distinctUntilChanged()
                .collect { (detent, visible, showsToolbar) ->
                    reportState(detent, visible, showsToolbar)
                }
        }

        val gridState = rememberLazyStaggeredGridState()
        LaunchedEffect(sheet) {
            snapshotFlow { offset > 0.5f }
                .distinctUntilChanged()
                .collectLatest { if (it) gridState.animateScrollToItem(0) }
        }
        val currentSorting by rememberUpdatedState(sorting)
        LaunchedEffect(Unit) {
            snapshotFlow { currentSorting }
                .drop(1)
                .collectLatest { gridState.animateScrollToItem(0) }
        }

        val connection =
            remember(anchors, flingThreshold) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source != NestedScrollSource.UserInput ||
                            available.y >= 0 ||
                            sheet.requireOffset() <= 0f
                        ) {
                            return Offset.Zero
                        }
                        startDrag()
                        return Offset(0f, sheet.dispatchRawDelta(available.y))
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source != NestedScrollSource.UserInput ||
                            available.y <= 0
                        ) {
                            return Offset.Zero
                        }
                        startDrag()
                        return Offset(0f, sheet.dispatchRawDelta(available.y))
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!isDragging) return Velocity.Zero
                        if (sheet.requireOffset() <= 0f) {
                            isDragging = false
                            settle(pending ?: SheetDetent.Fullscreen, 0f)
                            return Velocity.Zero
                        }
                        release(available.y)
                        return available
                    }
                }
            }

        val isAboveNormal by remember(normalOffset) {
            derivedStateOf { offset < normalOffset - 1f }
        }
        BackHandler(enabled = isAboveNormal) {
            if (current != SheetDetent.Normal) {
                settle(SheetDetent.Normal, 0f)
                onSetDetent(SheetDetent.Normal)
            }
        }

        val isMinimized = current == SheetDetent.Minimized
        val minimizedScale by animateFloatAsState(
            if (isMinimized) MINIMIZED_SCALE else 1f,
            SettleSpring,
        )
        val contentAlpha by animateFloatAsState(
            if (isMinimized) MINIMIZED_ALPHA else 1f,
            SettleSpring,
        )
        val isFullscreen by remember { derivedStateOf { progress > 1 - EPSILON } }
        val hidesHandle by remember { derivedStateOf { progress > HANDLE_PROGRESS } }
        val corner by animateDpAsState(
            if (isFullscreen) 0.dp else SheetCorner,
            tween(CORNER_MILLIS),
        )
        val handleAlpha by animateFloatAsState(if (hidesHandle) 0f else 1f, tween(CORNER_MILLIS))
        val restingColor = MaterialTheme.colorScheme.surfaceContainerLow
        val fullscreenColor = MaterialTheme.colorScheme.surface
        val restingCard =
            if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) LightCard else DarkCard
        val fullscreenCard = MaterialTheme.colorScheme.surfaceContainerHigh
        val cardBackground =
            remember(restingCard, fullscreenCard) {
                { lerp(restingCard, fullscreenCard, progress) }
            }
        val topInset =
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + ToolbarHeight
        val topInsetPx = with(density) { topInset.toPx() }
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offset.roundToInt()) }
                .graphicsLayer {
                    val width = size.width
                    val sidePadding = SidePadding.toPx() * (1 - progress)
                    val scale = minimizedScale * (width - sidePadding * 2) / width
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }.clip(RoundedCornerShape(topStart = corner, topEnd = corner))
                .drawBehind { drawRect(lerp(restingColor, fullscreenColor, progress)) }
                .nestedScroll(connection)
                .draggable(
                    state = rememberDraggableState { sheet.dispatchRawDelta(it) },
                    orientation = Orientation.Vertical,
                    onDragStarted = { startDrag() },
                    onDragStopped = { release(it) },
                ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ).graphicsLayer {
                        alpha = contentAlpha
                        translationY = -minOf(offset, topInsetPx)
                    },
            ) {
                if (images.isEmpty()) {
                    EmptyList(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = topInset)
                                .height(SHEET_NORMAL_HEIGHT.dp)
                                .wrapContentSize(),
                        placeholders = placeholders,
                        placeholderChange = emptyResultCount,
                        hint = hint,
                    )
                } else {
                    ImageGrid(
                        images = images,
                        scheme = scheme,
                        maxRange = maxRange,
                        onImageClick = onImageClick,
                        cardBackground = cardBackground,
                        state = gridState,
                        contentPadding =
                            PaddingValues(top = topInset, bottom = bottomInset),
                        padding = GridPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = HandleTop)
                    .size(HandleWidth, HandleHeight)
                    .graphicsLayer { alpha = handleAlpha }
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA),
                        CircleShape,
                    ),
            )
            if (isMinimized) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSetDetent(SheetDetent.Normal) },
                )
            }
            AnimatedVisibility(
                visible = showsMapChrome,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                        ).padding(CapsulePadding)
                        .graphicsLayer { alpha = contentAlpha },
                enter = fadeIn(ChromeFade),
                exit = fadeOut(ChromeFade),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(isLoading, enter = fadeIn(), exit = fadeOut()) {
                        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        }
                    }
                    ControlsPanel {
                        Row(Modifier.padding(horizontal = 2.dp)) {
                            MenuButton(
                                onClick = onFavoritesClick,
                                icon = Icons.Rounded.StarOutline,
                                description = stringResource(R.string.list_favorites),
                            )
                            MenuButton(
                                onClick = onSettingsClick,
                                icon = Icons.Outlined.Settings,
                                description = stringResource(R.string.settings),
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !showsMapChrome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(ChromeFade),
            exit = fadeOut(ChromeFade),
        ) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = {
                        settle(SheetDetent.Normal, 0f)
                        onSetDetent(SheetDetent.Normal)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.padding(horizontal = 12.dp).size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                    SortMenu(current = sorting, onSelect = onSortingChange)
                    IconButton(onClick = onFavoritesClick) {
                        Icon(
                            Icons.Rounded.StarOutline,
                            contentDescription = stringResource(R.string.list_favorites),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = fullscreenColor.copy(alpha = progress),
                    ),
            )
        }
    }
}

private val SettleSpring = spring<Float>(dampingRatio = 1f, stiffness = 158f)
private const val FLING_THRESHOLD = 500f
private const val TOOLBAR_PROGRESS = 0.01f
private const val MAX_RELATIVE_VELOCITY = 30f
private const val HANDLE_PROGRESS = 0.35f
private const val MINIMIZED_SCALE = 0.9f
private const val MINIMIZED_ALPHA = 0.5f
private const val EPSILON = 0.001f
private const val CORNER_MILLIS = 250
private val SheetCorner = 28.dp
private val SidePadding = 8.dp
private val GridPadding = 13.dp
private val CapsulePadding = 15.dp
private val ToolbarHeight = 64.dp
private val HandleTop = 4.5.dp
private val HandleWidth = 32.dp
private val HandleHeight = 4.dp
private const val HANDLE_ALPHA = 0.4f
internal val ChromeFade = tween<Float>(CORNER_MILLIS)
private val LightCard = Color.White.copy(alpha = 0.6f)
private val DarkCard = Color.Black.copy(alpha = 0.3f)
