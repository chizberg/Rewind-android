package com.chizberg.rewind.features.map.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import com.chizberg.rewind.features.map.MapControlItem
import com.chizberg.rewind.ui.toComposeColor
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush as GradientBrush

/**
 * The map's filter chrome: a toolbar row with the year-picker toggle and the photo/painting switch,
 * plus the year selector that expands underneath it. Changes are dispatched up as
 * [com.chizberg.rewind.features.map.MapAction.External.Ui.FiltersChanged] /
 * [com.chizberg.rewind.features.map.MapAction.External.Ui.Controls.SetExpandedItems]. Port of iOS
 * `FloatingMenu` trimmed to the filters scope — the map-type, search and location buttons arrive
 * with their milestones.
 *
 * Expansion mirrors iOS (`FloatingMenuImpl`: the row on top, the expanded item below it, so the
 * selector grows into the gap above the preview strip) with one divergence: the clock stays in the
 * row as an [FilledIconToggleButton] rather than being morphed away into the expanded panel, so it
 * doubles as the close affordance and iOS's separate close button is dropped (design canon:
 * `IconToggleButton` `schedule`).
 */
@Composable
fun FiltersControl(
    filters: ImageRequestFilters,
    scheme: GradientScheme,
    expandedItems: Set<MapControlItem>,
    onFiltersChanged: (ImageRequestFilters) -> Unit,
    onExpandedItemsChanged: (Set<MapControlItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTimePickerExpanded = MapControlItem.TimePicker in expandedItems
    val isPainting = filters.imageKind.isPainting
    val kindLabel =
        stringResource(
            if (isPainting) R.string.image_kind_paintings else R.string.image_kind_photos,
        )
    Column(modifier.fillMaxWidth()) {
        ControlsPanel(Modifier.padding(horizontal = ScreenPadding)) {
            Row(
                modifier = Modifier.padding(PanelPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ControlSpacing),
            ) {
                MenuToggle(
                    checked = isTimePickerExpanded,
                    onCheckedChange = { expanded ->
                        onExpandedItemsChanged(
                            if (expanded) {
                                expandedItems + MapControlItem.TimePicker
                            } else {
                                expandedItems - MapControlItem.TimePicker
                            },
                        )
                    },
                    icon = Icons.Rounded.Schedule,
                    description = stringResource(R.string.year_range),
                    // Nothing else on screen says the filter is on while the picker is closed, and
                    // the accent tint alone (iOS's only cue) went unnoticed — so a notification-style
                    // dot marks it, with the range itself left to the screen reader.
                    badged = filters.isRangeModified,
                    stateLabel =
                        "${filters.yearRange.first} - ${filters.yearRange.last}"
                            .takeIf { filters.isRangeModified },
                    isActive = filters.isRangeModified,
                )
                MenuToggle(
                    checked = isPainting,
                    onCheckedChange = { checked ->
                        val kind =
                            if (checked) {
                                ImageRequestFilters.ImageKind.Painting
                            } else {
                                ImageRequestFilters.ImageKind.Photo
                            }
                        onFiltersChanged(filters.copy(imageKind = kind))
                    },
                    // FILL-axis swap (iOS `paintbrush.pointed` / `.fill`) plus the mode spelled out:
                    // the tonal container alone reads as decoration and is easy to miss.
                    icon = if (isPainting) Icons.Rounded.Brush else Icons.Outlined.Brush,
                    description = kindLabel,
                    label = kindLabel.takeIf { isPainting },
                )
            }
        }
        // The side inset lives *inside* the animated content on purpose: this is the only clipped
        // node on screen, and a panel flush with the clip edge had its drop shadow cut off for the
        // length of the expansion and then snap in when the clip lifted.
        AnimatedVisibility(visible = isTimePickerExpanded) {
            ControlsPanel(
                Modifier
                    .padding(start = ScreenPadding, end = ScreenPadding, top = PanelGap)
                    .widthIn(max = MaxWidth)
                    .fillMaxWidth(),
            ) {
                YearSelector(
                    yearRange = filters.yearRange,
                    maxRange = filters.imageKind.maxRange,
                    scheme = scheme,
                    onYearRangeChange = { onFiltersChanged(filters.copy(yearRange = it)) },
                    modifier =
                        Modifier.padding(
                            horizontal = SelectorPaddingH,
                            vertical = SelectorPaddingV,
                        ),
                )
            }
        }
    }
}

/** Glass -> tonal surface: RewindGlass = `surfaceContainerHigh`, floating-toolbar depth. */
@Composable
private fun ControlsPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PanelCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = PanelElevation,
        content = content,
    )
}

/**
 * One control in the toolbar row. Port of the iOS `FloatingMenuButton` /
 * `TitledFloatingMenuButton` pair: a glyph that fills with `secondaryContainer` while [checked],
 * optionally carrying a [label] to its right (iOS shows its title only for a second after a change,
 * via `ValueChangeIndicator`; here it stays, because on Android the tonal fill alone reads as
 * decoration and the state was easy to miss). [isActive] is iOS's accent tint for a control that is
 * doing something while unchecked — the one brand accent in the chrome.
 *
 * Sized to a 48dp pill so it nests concentrically inside the panel's 28dp corner (48/2 + 4dp panel
 * padding = 28), and it stays a pill once a label widens it. Semantics are set here rather than on
 * the glyph so a labelled control isn't announced twice.
 */
@Composable
private fun MenuToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    stateLabel: String? = null,
    badged: Boolean = false,
    isActive: Boolean = false,
) {
    // Everything that can change while the control stays on screen animates: the fill and the glyph
    // colour cross-fade, and the label expands the pill instead of snapping its width.
    val containerColor by
        animateColorAsState(
            if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            label = "menuToggleContainer",
        )
    val contentColor by
        animateColorAsState(
            when {
                checked -> MaterialTheme.colorScheme.onSecondaryContainer
                isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = "menuToggleContent",
        )
    // Held over so the label has something to draw while it animates back out.
    var lastLabel by remember { mutableStateOf(label) }
    if (label != null) lastLabel = label
    val state = stateLabel ?: label
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier =
            modifier
                .height(ToggleHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (state != null) stateDescription = state
                },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TogglePaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (badged) Badge(containerColor = MaterialTheme.colorScheme.primary)
                },
            ) {
                Icon(icon, contentDescription = null)
            }
            AnimatedVisibility(
                visible = label != null,
                // Anchored at the start so the label unrolls out of the glyph, first letter first —
                // the default end-anchor reveals its tail first, which reads as garbled text.
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                Text(
                    // The gap rides with the label so a collapsed one leaves no phantom space.
                    modifier = Modifier.padding(start = ToggleLabelGap),
                    text = lastLabel.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
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
 */
@Composable
private fun YearSelector(
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
 * changes under the finger (`animateColorAsState`). The 1dp `outlineVariant` ring is the year-fill
 * canon (readable on any surface). The touch target spans the selector's full height, and the
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
            // No shadow of its own: depth is stated once, by the panel this sits inside (M3 doesn't
            // stack elevation, and its own slider thumbs carry no shadow). Separation from the
            // gradient underneath is the canon 1dp ring's job.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

// The panels are floating toolbars (RewindGlass): rounded, capped in width on large screens. Both
// come out 56dp tall, i.e. a true pill at a 28dp corner, and both hold their content concentrically:
// the gap around it is exactly `PanelCorner - <content corner>`, so the curves nest instead of
// drifting apart at the extremes (toggle 48/2 + 4 = 28; thumb 30/2 + 13 = 28).
private val MaxWidth = 520.dp
private val ScreenPadding = 16.dp
private val PanelCorner = 28.dp

// M3 elevation level 2 — the canon depth for floating chrome over the map (design/01-map.md §6,
// реш. #11: M3-native shadowElevation, not a hand-tuned match for the iOS drop shadow). Nothing
// inside the panel adds a second shadow.
private val PanelElevation = 3.dp
private val PanelGap = 8.dp
private val PanelPadding = 4.dp
private val ControlSpacing = 4.dp

private val ToggleHeight = 48.dp
private val TogglePaddingH = 12.dp
private val ToggleLabelGap = 6.dp

// Thumb geometry echoes iOS `ThumbView` (60x30, corner 15) at a Compose scale; the year label sits
// centred. The track thickness echoes iOS `lineHeight` (7), the selector height iOS's `frame(50)`.
private val SelectorHeight = 50.dp
private val SelectorPaddingH = 13.dp
private val SelectorPaddingV = 3.dp
private val ThumbWidth = 54.dp
private val ThumbHeight = 30.dp
private val ThumbCorner = 15.dp
private val TrackThickness = 8.dp
