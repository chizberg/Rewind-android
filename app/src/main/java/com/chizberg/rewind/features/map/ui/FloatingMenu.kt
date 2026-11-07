package com.chizberg.rewind.features.map.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocationDisabled
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.R
import com.chizberg.rewind.core.util.lerp
import com.chizberg.rewind.core.util.lerpParameter
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.domain.MapType
import com.chizberg.rewind.features.imagelist.ui.fadeBrush
import com.chizberg.rewind.ui.toComposeColor
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush as GradientBrush

@Composable
fun FloatingMenu(
    filters: ImageRequestFilters,
    scheme: GradientScheme,
    mapType: MapType,
    isTimePickerVisible: Boolean,
    onFiltersChanged: (ImageRequestFilters) -> Unit,
    onMapTypeChanged: (MapType) -> Unit,
    onTimePickerVisibleChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        ControlsPanel {
            Row(Modifier.padding(PanelPadding)) {
                Box {
                    MenuButton(
                        onClick = { onTimePickerVisibleChanged(true) },
                        icon = Icons.Rounded.Schedule,
                        description = stringResource(R.string.year_range),
                        tint =
                            if (filters.isRangeModified) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    DropdownMenu(
                        expanded = isTimePickerVisible,
                        onDismissRequest = { onTimePickerVisibleChanged(false) },
                        shape = RoundedCornerShape(PanelCorner),
                    ) {
                        YearSelector(
                            yearRange = filters.yearRange,
                            maxRange = filters.imageKind.maxRange,
                            scheme = scheme,
                            onYearRangeChange = { onFiltersChanged(filters.copy(yearRange = it)) },
                            modifier = Modifier.width(YearPopoverWidth).padding(SelectorPadding),
                        )
                    }
                }
                MenuPicker(
                    icon = if (mapType.isHybrid) Icons.Rounded.Public else Icons.Outlined.Map,
                    description =
                        stringResource(
                            if (mapType.isHybrid) {
                                R.string.map_type_satellite
                            } else {
                                R.string.map_type_scheme
                            },
                        ),
                    selection = mapType,
                    options =
                        listOf(
                            PickerOption(
                                MapType.Scheme,
                                R.string.map_type_scheme,
                                Icons.Outlined.Map,
                            ),
                            PickerOption(
                                MapType.Hybrid,
                                R.string.map_type_satellite,
                                Icons.Rounded.Public,
                            ),
                        ),
                    onSelect = onMapTypeChanged,
                )
                val isPainting = filters.imageKind.isPainting
                MenuPicker(
                    icon = if (isPainting) Icons.Rounded.Brush else Icons.Outlined.Brush,
                    description =
                        stringResource(
                            if (isPainting) {
                                R.string.image_kind_paintings
                            } else {
                                R.string.image_kind_photos
                            },
                        ),
                    tint =
                        if (isPainting) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    selection = filters.imageKind,
                    options =
                        listOf(
                            PickerOption(
                                ImageRequestFilters.ImageKind.Photo,
                                R.string.image_kind_photos,
                                Icons.Outlined.PhotoCamera,
                            ),
                            PickerOption(
                                ImageRequestFilters.ImageKind.Painting,
                                R.string.image_kind_paintings,
                                Icons.Outlined.Brush,
                            ),
                        ),
                    onSelect = { onFiltersChanged(filters.copy(imageKind = it)) },
                )
            }
        }
    }
}

@Composable
fun MapTopBar(
    locationAccessGranted: Boolean,
    isCompactHeight: Boolean,
    onSearchClick: () -> Unit,
    onLocationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tail = if (isCompactHeight) 0.dp else TopBarFadeTail
    val fade = MaterialTheme.colorScheme.surface
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .drawBehind {
                    val height = size.height + tail.toPx()
                    drawRect(fadeBrush(fade, height), size = size.copy(height = height))
                }.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                    ),
                ).height(TopBarHeight)
                .padding(start = TopBarTitlePadding, end = TopBarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        ControlsPanel {
            Row(Modifier.padding(horizontal = CapsulePadding)) {
                MenuButton(
                    onClick = onSearchClick,
                    icon = Icons.Rounded.Search,
                    description = stringResource(R.string.search),
                )
                MenuButton(
                    onClick = onLocationClick,
                    icon =
                        if (locationAccessGranted) {
                            Icons.Rounded.MyLocation
                        } else {
                            Icons.Rounded.LocationDisabled
                        },
                    description = stringResource(R.string.my_location),
                )
            }
        }
    }
}

@Composable
internal fun ControlsPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PanelCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border =
            BorderStroke(
                Hairline,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = HAIRLINE_ALPHA),
            ),
        content = content,
    )
}

private class PickerOption<T>(
    val value: T,
    @StringRes val title: Int,
    val icon: ImageVector,
)

@Composable
private fun <T> MenuPicker(
    icon: ImageVector,
    description: String,
    selection: T,
    options: List<PickerOption<T>>,
    onSelect: (T) -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MenuButton(
            onClick = { expanded = true },
            icon = icon,
            description = description,
            tint = tint,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.title)) },
                    leadingIcon = { Icon(option.icon, contentDescription = null) },
                    trailingIcon = {
                        if (option.value == selection) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}

@Composable
internal fun MenuButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(ToggleHeight),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description)
        }
    }
}

/**
 * The dual-thumb year selector. Port of iOS `YearSelector` / `YearSelectorImpl` + `ThumbView`,
 * hand-rolled rather than built on M3 `RangeSlider` (design canon reversed here): the stock slider
 * centres each thumb on its value and hard-stops one thumb at the other, while iOS anchors the
 * value to the thumb's *inner edge* — so the two labels never overlap — and lets a thumb **push**
 * the other along (`move(thumb:xDiff:)` recursion). Neither is reachable through the slider's
 * public state, which coerces `activeRangeStart <= activeRangeEnd` inside its own gesture.
 *
 * The view owns the thumb positions while dragging (iOS `ThumbView.value`, a plain `0...1`), and
 * re-seeds from [yearRange] whenever [maxRange] changes, i.e. on a photos<->paintings flip (iOS
 * `updateMaxRange`) — by then the reducer has already reset the range to the new kind's full span.
 * A year is handed up only when it actually changes (iOS `rangeDidChange`; the reducer debounces
 * the reload), and positions are read in the layout/draw phase so a drag never recomposes.
 *
 * Public because the onboarding's second page embeds the very same control to explain
 * colour-by-year — iOS reaches for the map's `YearSelector` there too (`AnnotationsScreen.swift`),
 * on a purely local range that never touches the map's filters.
 */
@Composable
fun YearSelector(
    yearRange: IntRange,
    maxRange: IntRange,
    scheme: GradientScheme,
    onYearRangeChange: (IntRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbWidthPx = with(LocalDensity.current) { ThumbWidth.toPx() }
    val startFraction =
        remember(maxRange) { mutableFloatStateOf(fractionOf(yearRange.first, maxRange)) }
    val endFraction =
        remember(maxRange) { mutableFloatStateOf(fractionOf(yearRange.last, maxRange)) }
    val reported = remember(maxRange) { mutableStateOf(yearRange) }
    // Only the crossed year matters downstream, so the labels and tints recompose once per year
    // rather than once per pixel of drag.
    val startYear by
        remember(maxRange) { derivedStateOf { yearOf(startFraction.floatValue, maxRange) } }
    val endYear by
        remember(maxRange) { derivedStateOf { yearOf(endFraction.floatValue, maxRange) } }

    BoxWithConstraints(modifier.height(SelectorHeight), contentAlignment = Alignment.CenterStart) {
        // Both thumbs' anchors travel [thumbWidth, width - thumbWidth]: the anchor is the start
        // thumb's right edge and the end thumb's left edge (iOS `ThumbView.valueSide`), so at either
        // extreme the thumb body still lands inside the track.
        val minAnchor = thumbWidthPx
        val maxAnchor = (constraints.maxWidth - thumbWidthPx).coerceAtLeast(minAnchor + 1f)

        fun anchorOf(fraction: Float): Float = minAnchor + (maxAnchor - minAnchor) * fraction

        fun fractionAt(anchor: Float): Float =
            ((anchor - minAnchor) / (maxAnchor - minAnchor)).coerceIn(0f, 1f)

        fun report() {
            val range =
                yearOf(startFraction.floatValue, maxRange)..yearOf(endFraction.floatValue, maxRange)
            if (range != reported.value) {
                reported.value = range
                onYearRangeChange(range)
            }
        }

        // Collision handling: a thumb dragged past its neighbour carries it along instead of
        // stopping dead (iOS `move(thumb:xDiff:)` recursing into the other thumb). Both clamp at
        // the same bound, so the pushed thumb simply pins the dragged one at the end of the track.
        fun moveStart(fraction: Float) {
            startFraction.floatValue = fraction
            if (endFraction.floatValue < fraction) endFraction.floatValue = fraction
            report()
        }

        fun moveEnd(fraction: Float) {
            endFraction.floatValue = fraction
            if (startFraction.floatValue > fraction) startFraction.floatValue = fraction
            report()
        }

        val startDrag =
            rememberDraggableState { delta ->
                moveStart(fractionAt(anchorOf(startFraction.floatValue) + delta))
            }
        val endDrag =
            rememberDraggableState { delta ->
                moveEnd(fractionAt(anchorOf(endFraction.floatValue) + delta))
            }

        GradientTrack(
            scheme = scheme,
            gradientStartX = minAnchor,
            gradientEndX = maxAnchor,
            selectionStartX = { anchorOf(startFraction.floatValue) },
            selectionEndX = { anchorOf(endFraction.floatValue) },
        )

        YearThumb(
            year = startYear,
            scheme = scheme,
            maxRange = maxRange,
            label = stringResource(R.string.year_range_start),
            onYearRequested = { moveStart(fractionOf(it, maxRange)) },
            modifier =
                Modifier
                    .offset {
                        val left = anchorOf(startFraction.floatValue) - thumbWidthPx
                        IntOffset(left.roundToInt(), 0)
                    }.draggable(state = startDrag, orientation = Orientation.Horizontal),
        )
        YearThumb(
            year = endYear,
            scheme = scheme,
            maxRange = maxRange,
            label = stringResource(R.string.year_range_end),
            onYearRequested = { moveEnd(fractionOf(it, maxRange)) },
            modifier =
                Modifier
                    .offset { IntOffset(anchorOf(endFraction.floatValue).roundToInt(), 0) }
                    .draggable(state = endDrag, orientation = Orientation.Horizontal),
        )
    }
}

/**
 * The gradient track. Port of iOS `YearSelectorImpl`'s gradient `line` + grey `lineShadow`s: the
 * [scheme]'s ramp frozen across the whole value span (so the track never shifts while dragging),
 * with everything outside the selection greyed with `outlineVariant`. The gradient is normalized to
 * the anchors' own span ([gradientStartX]..[gradientEndX]), so the colour under a thumb's anchor is
 * exactly that thumb's tint. Selection bounds come in as lambdas: they are read in the draw phase,
 * so dragging repaints without recomposing.
 */
@Composable
private fun GradientTrack(
    scheme: GradientScheme,
    gradientStartX: Float,
    gradientEndX: Float,
    selectionStartX: () -> Float,
    selectionEndX: () -> Float,
    modifier: Modifier = Modifier,
) {
    val gradientStops =
        remember(scheme) {
            scheme.value.map { it.position.toFloat() to it.value.toComposeColor() }.toTypedArray()
        }
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(TrackThickness)) {
        val radius = CornerRadius(size.height / 2f)
        val outline =
            Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
        clipPath(outline) {
            drawRect(
                brush =
                    GradientBrush.horizontalGradient(
                        colorStops = gradientStops,
                        startX = gradientStartX,
                        endX = gradientEndX,
                    ),
            )
            val startX = selectionStartX()
            val endX = selectionEndX()
            if (startX > 0f) {
                drawRect(color = inactiveColor, size = Size(startX, size.height))
            }
            if (endX < size.width) {
                drawRect(
                    color = inactiveColor,
                    topLeft = Offset(endX, 0f),
                    size = Size(size.width - endX, size.height),
                )
            }
        }
    }
}

/**
 * One year-tinted thumb carrying its year. Port of iOS `ThumbView`: tinted by [GradientScheme.color]
 * for its [year] with a legible [GradientScheme.foreground] label; retinted in real time as the year
 * changes under the finger (`animateColorAsState`). The year-fill canon's `outlineVariant` ring
 * keeps it readable on any surface, at the same dimmed [Hairline] the panels wear. The touch target spans the selector's full height, and the
 * semantics stand in for what the stock slider would have given ([onYearRequested] is TalkBack's
 * "adjust" path).
 */
@Composable
private fun YearThumb(
    year: Int,
    scheme: GradientScheme,
    maxRange: IntRange,
    label: String,
    onYearRequested: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = scheme.color(year, maxRange)
    val containerColor by animateColorAsState(tint.toComposeColor(), label = "yearThumbTint")
    val contentColor = scheme.foreground(tint).toComposeColor()
    Box(
        modifier =
            modifier
                .size(width = ThumbWidth, height = SelectorHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = year.toString()
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            current = year.toFloat(),
                            range = maxRange.first.toFloat()..maxRange.last.toFloat(),
                        )
                    setProgress { target ->
                        onYearRequested(target.roundToInt())
                        true
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(width = ThumbWidth, height = ThumbHeight),
            shape = RoundedCornerShape(ThumbCorner),
            color = containerColor,
            contentColor = contentColor,
            // No shadow of its own: there are none left in the chrome, and M3 wouldn't stack
            // elevation anyway (its own slider thumbs carry none either). Separation from the
            // gradient underneath is the hairline's job.
            border =
                BorderStroke(
                    Hairline,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = HAIRLINE_ALPHA),
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
            }
        }
    }
}

/** A year's `0..1` position within [maxRange], clamped at both ends (iOS `lerpParameter`). */
private fun fractionOf(
    year: Int,
    maxRange: IntRange,
): Float =
    lerpParameter(
        value = year.toDouble(),
        lowerBound = maxRange.first.toDouble(),
        upperBound = maxRange.last.toDouble(),
    ).toFloat()

/** The year at a `0..1` position within [maxRange] (iOS `year(from:)`). */
private fun yearOf(
    fraction: Float,
    maxRange: IntRange,
): Int =
    lerp(
        at = fraction.toDouble(),
        lhs = maxRange.first.toDouble(),
        rhs = maxRange.last.toDouble(),
    ).roundToInt()

private val PanelCorner = 28.dp

// The one outline the chrome uses — panels and thumbs alike — instead of the canon's elevation
// level 2 (see [ControlsPanel]); nothing here carries a shadow now, so nothing needs a second one
// either. `outlineVariant` is already M3's quietest outline, and at full strength it still drew the
// eye over a busy map, so it runs at half: enough to state an edge, not enough to be read as one
// more thing on screen.
private val Hairline = 1.dp
private const val HAIRLINE_ALPHA = 0.5f
private val PanelPadding = 4.dp
private val TopBarPadding = 15.dp
private val TopBarTitlePadding = 16.dp
private val TopBarHeight = 64.dp
private val TopBarFadeTail = 16.dp
private val CapsulePadding = 2.dp
private val ToggleHeight = 48.dp
internal val SearchCapsuleInset = TopBarPadding + ToggleHeight * 2 + CapsulePadding * 2
private val YearPopoverWidth = 352.dp
private val SelectorPadding = 16.dp

// Thumb geometry echoes iOS `ThumbView` (60x30, corner 15) at a Compose scale; the year label sits
// centred. The track thickness echoes iOS `lineHeight` (7), the selector height iOS's `frame(50)`.
private val SelectorHeight = 50.dp
private val ThumbWidth = 54.dp
private val ThumbHeight = 30.dp
private val ThumbCorner = 15.dp
private val TrackThickness = 8.dp
