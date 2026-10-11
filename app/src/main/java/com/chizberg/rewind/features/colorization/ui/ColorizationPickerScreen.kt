package com.chizberg.rewind.features.colorization.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.R
import com.chizberg.rewind.app.RewindAlert
import com.chizberg.rewind.colorization.ColorizationFileState
import com.chizberg.rewind.colorization.ColorizationModelId
import com.chizberg.rewind.features.colorization.ColorizationFileAction
import com.chizberg.rewind.features.colorization.ColorizationPickerAction
import com.chizberg.rewind.features.colorization.ColorizationPickerModel
import com.chizberg.rewind.ui.rainbowColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The model picker. Port of iOS `ColorizationPickerScreen`: the before/after demo, the pitch, the
 * models with their download controls, and — opened from a photo — a "Colorize" button once a
 * model is picked.
 *
 * Divergences: iOS presents it as a zoomed sheet from the colorize button (from a photo) or pushes
 * it in the settings stack; here it is one more overlay layer either way, with a top bar carrying
 * the back arrow and iOS's settings title. Deleting a model is iOS's swipe action, built from M3's
 * [SwipeToDismissBox].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorizationPickerScreen(
    model: ColorizationPickerModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val showsColorize = state.canColorize && state.picked != null

    // iOS scrolls the button into view the moment it appears.
    LaunchedEffect(showsColorize) {
        if (showsColorize) listState.animateScrollToItem(PICKER_ITEMS)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.colorization_model)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    top = insets.calculateTopPadding(),
                    bottom = insets.calculateBottomPadding() + ScreenPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Pitch(Modifier.widthIn(max = MaxContentWidth)) }

            item {
                ModelSection(
                    ids = state.modelIds,
                    fileStates = state.fileStates,
                    picked = state.picked,
                    sizes = state.sizes,
                    showsFooter = state.canColorize,
                    onAction = model::invoke,
                    modifier = Modifier.widthIn(max = MaxContentWidth),
                )
            }

            item {
                AnimatedVisibility(visible = showsColorize) {
                    Button(
                        onClick = { model(ColorizationPickerAction.Colorize) },
                        modifier =
                            Modifier
                                .widthIn(max = MaxContentWidth)
                                .fillMaxWidth()
                                .padding(horizontal = ScreenPadding),
                    ) {
                        Text(stringResource(R.string.colorize))
                    }
                }
            }
        }
    }

    state.alert?.let { params ->
        RewindAlert(params = params, onDismiss = { model(ColorizationPickerAction.DismissAlert) })
    }
}

/** The demo, the title, the rainbow promo line, the lead and its four points. */
@Composable
private fun Pitch(modifier: Modifier = Modifier) {
    Column(modifier) {
        ColorizationDemo(Modifier.fillMaxWidth())
        Column(
            Modifier.padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(PitchGap),
        ) {
            Text(
                text = stringResource(R.string.image_colorization),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.colorization_feature_promo),
                style =
                    MaterialTheme.typography.labelMedium.merge(
                        TextStyle(
                            brush = Brush.horizontalGradient(rainbowColors(exposure = -0.5f)),
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "smcp",
                        ),
                    ),
            )
            Text(
                text = stringResource(R.string.colorization_feature_lead),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(BulletGap)) {
                listOf(
                    R.string.colorization_feature_free,
                    R.string.colorization_feature_download_once,
                    R.string.colorization_feature_seconds,
                    R.string.colorization_feature_delete_anytime,
                ).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(BulletGap)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(line), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ModelSection(
    ids: List<ColorizationModelId>,
    fileStates: Map<ColorizationModelId, ColorizationFileState>,
    picked: ColorizationModelId?,
    sizes: Map<ColorizationModelId, Long>?,
    showsFooter: Boolean,
    onAction: (ColorizationPickerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(horizontal = ScreenPadding)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HeaderGap),
    ) {
        Text(
            text = stringResource(R.string.pick_a_model),
            modifier = Modifier.padding(horizontal = HeaderInset),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(CardCorner),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column {
                ids.forEachIndexed { index, id ->
                    if (index >
                        0
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    val fileState = fileStates.getValue(id)
                    DeletableRow(
                        enabled = fileState == ColorizationFileState.Downloaded,
                        onDelete = {
                            onAction(
                                ColorizationPickerAction.File(id, ColorizationFileAction.Delete),
                            )
                        },
                    ) {
                        ModelRow(
                            id = id,
                            state = fileState,
                            isPicked = picked == id,
                            size = sizes?.get(id),
                            onPick = { onAction(ColorizationPickerAction.Pick(id)) },
                            onDownload = {
                                onAction(
                                    ColorizationPickerAction.File(
                                        id,
                                        ColorizationFileAction.Download,
                                    ),
                                )
                            },
                            onStop = {
                                onAction(
                                    ColorizationPickerAction.File(
                                        id,
                                        ColorizationFileAction.CancelDownload,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        if (showsFooter) {
            Text(
                text = stringResource(R.string.colorization_change_later),
                modifier = Modifier.padding(horizontal = HeaderInset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** iOS `.swipeActions { Delete }`, offered only on a downloaded model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeletableRow(
    enabled: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            swipe.reset()
        }
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = RowPaddingH),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) { content() }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ModelRow(
    id: ColorizationModelId,
    state: ColorizationFileState,
    isPicked: Boolean,
    size: Long?,
    onPick: () -> Unit,
    onDownload: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(id.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    listOfNotNull(
                        id.modelName,
                        size?.let { Formatter.formatShortFileSize(context, it) },
                    ).joinToString(" | "),
                modifier = Modifier.alpha(SECONDARY_ALPHA),
                style =
                    MaterialTheme.typography.labelSmall.merge(
                        TextStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "smcp"),
                    ),
            )
            Text(
                text = stringResource(id.description),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        DownloadIndicator(state, isPicked, onDownload, onStop)
    }
}

/** iOS `DownloadIndicator`: the 28pt control at the end of a model's row. */
@Composable
private fun DownloadIndicator(
    state: ColorizationFileState,
    isPicked: Boolean,
    onDownload: () -> Unit,
    onStop: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.primary
    Box(Modifier.size(IndicatorTarget), contentAlignment = Alignment.Center) {
        when (state) {
            ColorizationFileState.Available ->
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Outlined.ArrowCircleDown,
                        contentDescription = stringResource(R.string.download),
                        tint = tint,
                        modifier = Modifier.size(IndicatorSize),
                    )
                }

            is ColorizationFileState.Downloading -> {
                val stop = stringResource(R.string.cancel)
                Box(
                    Modifier
                        .size(IndicatorTarget)
                        .clickable(role = Role.Button, onClick = onStop)
                        .semantics { contentDescription = stop },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.size(IndicatorSize),
                        strokeWidth = IndicatorStroke,
                    )
                    Box(
                        Modifier
                            .size(StopSquare)
                            .background(tint, RoundedCornerShape(StopCorner)),
                    )
                }
            }

            ColorizationFileState.Installing ->
                CircularProgressIndicator(
                    modifier = Modifier.size(IndicatorSize),
                    strokeWidth = IndicatorStroke,
                )

            ColorizationFileState.Downloaded ->
                if (isPicked) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(IndicatorSize),
                    )
                }
        }
    }
}

/**
 * iOS `ColorizationDemo`: the colorized photo over the original, cut at a divider the finger drags
 * (snapping to the edges), which wiggles once on appearance to show it can be dragged.
 */
@Composable
private fun ColorizationDemo(modifier: Modifier = Modifier) {
    val position = remember { Animatable(0.5f) }
    LaunchedEffect(Unit) {
        position.animateTo(WIGGLE_FIRST, tween(WIGGLE_SHORT_MS))
        position.animateTo(WIGGLE_SECOND, tween(WIGGLE_LONG_MS))
        position.animateTo(0.5f, tween(WIGGLE_SHORT_MS))
    }
    val density = LocalDensity.current
    val dividerWidthPx = with(density) { DividerHandleWidth.toPx() }
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val scope = rememberCoroutineScope()
        Box(
            Modifier.pointerInput(widthPx) {
                detectDragGestures { change, _ ->
                    val x = change.position.x
                    val snapped =
                        when {
                            x < dividerWidthPx / 2 -> 0f
                            x > widthPx - dividerWidthPx / 2 -> 1f
                            else -> x / widthPx
                        }
                    scope.launch {
                        position.animateTo(
                            snapped,
                            spring(
                                dampingRatio = DEMO_DAMPING,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
            },
        ) {
            Image(
                painter = painterResource(R.drawable.colorization_demo_before),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
            Image(
                painter = painterResource(R.drawable.colorization_demo_after),
                contentDescription = null,
                modifier =
                    Modifier
                        .matchParentSize()
                        .drawWithContent {
                            clipRect(left = size.width * position.value) {
                                this@drawWithContent.drawContent()
                            }
                        },
                contentScale = ContentScale.FillWidth,
            )
            DemoDivider(
                offsetPx = {
                    (widthPx * position.value)
                        .coerceIn(dividerWidthPx / 2, widthPx - dividerWidthPx / 2)
                        .roundToInt()
                },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun DemoDivider(
    offsetPx: () -> Int,
    modifier: Modifier = Modifier,
) {
    val half = with(LocalDensity.current) { (DividerHandleWidth / 2).roundToPx() }
    Box(modifier) {
        Box(
            Modifier
                .offset { IntOffset(offsetPx() - half, 0) }
                .width(DividerHandleWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
            Box(
                Modifier
                    .size(DividerHandleWidth, DividerHandleHeight)
                    .background(Color.White, RoundedCornerShape(50)),
            )
        }
    }
}

private val ColorizationModelId.title: Int
    get() =
        when (this) {
            ColorizationModelId.DDColorLarge -> R.string.colorization_model_default
            ColorizationModelId.Eccv16 -> R.string.colorization_model_compact
        }

/** Not translated on iOS either: the models' own names. */
private val ColorizationModelId.modelName: String
    get() =
        when (this) {
            ColorizationModelId.DDColorLarge -> "DDColor-Large"
            ColorizationModelId.Eccv16 -> "ECCV16"
        }

private val ColorizationModelId.description: Int
    get() =
        when (this) {
            ColorizationModelId.DDColorLarge -> R.string.colorization_ddcolor_description
            ColorizationModelId.Eccv16 -> R.string.colorization_eccv16_description
        }

/** The pitch and the model section come before the button in the list. */
private const val PICKER_ITEMS = 2
private const val SECONDARY_ALPHA = 0.5f
private const val WIGGLE_FIRST = 0.3f
private const val WIGGLE_SECOND = 0.7f
private const val WIGGLE_SHORT_MS = 700
private const val WIGGLE_LONG_MS = 1000
private const val DEMO_DAMPING = 0.7f

private val ScreenPadding = 16.dp
private val SectionSpacing = 24.dp
private val MaxContentWidth = 600.dp
private val PitchGap = 6.dp
private val BulletGap = 6.dp
private val HeaderGap = 8.dp
private val HeaderInset = 16.dp
private val CardCorner = 16.dp
private val RowPaddingH = 16.dp
private val RowPaddingV = 12.dp
private val RowGap = 12.dp
private val IndicatorTarget = 48.dp
private val IndicatorSize = 28.dp
private val IndicatorStroke = 2.dp
private val StopSquare = 9.dp
private val StopCorner = 2.dp
private val DividerHandleWidth = 10.dp
private val DividerHandleHeight = 50.dp
