package com.chizberg.rewind.features.map.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.LocationDisabled
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.chizberg.rewind.domain.MapType
import com.chizberg.rewind.features.map.MapControlItem
import com.chizberg.rewind.ui.toComposeColor
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.graphics.Brush as GradientBrush

/**
 * The map's floating menu: the filter controls (year-picker toggle, scheme/satellite switch,
 * photo/painting switch), the place-search and location buttons, plus the year selector that
 * expands underneath. Filter changes are dispatched up as
 * [com.chizberg.rewind.features.map.MapAction.External.Ui.FiltersChanged] /
 * [com.chizberg.rewind.features.map.MapAction.External.Ui.Controls.SetExpandedItems], and so are
 * the map-type and location taps (iOS `.locationTap` → `.right(.locationButtonTapped)`); the search
 * button goes to the *app* reducer instead (iOS `.searchTap` → `.left(.search(.present))`), so it
 * arrives as a plain [onSearchClick] callback. Port of iOS `FloatingMenu`, same item order.
 *
 * **The fixed items ride in their own bubble, pushed to the far edge**, mirroring iOS: there every
 * item wears its own capsule (`BackgroundModifier`) and a `Spacer()` splits the filter items from
 * the fixed ones (`search`, `location`). Grouping the glyphs by what they act on is the point — the
 * filters narrow *which images* load, while search and location move *where the map looks*, so a
 * single shared pill read as one menu of unrelated things.
 *
 * Expansion mirrors iOS (`FloatingMenuImpl`: the row on top, the expanded item below it, so the
 * selector grows into the gap above the preview strip) with one divergence: the clock stays in the
 * row as an [FilledIconToggleButton] rather than being morphed away into the expanded panel, so it
 * doubles as the close affordance and iOS's separate close button is dropped (design canon:
 * `IconToggleButton` `schedule`).
 */
@Composable
fun FloatingMenu(
    filters: ImageRequestFilters,
    scheme: GradientScheme,
    mapType: MapType,
    expandedItems: Set<MapControlItem>,
    locationAccessGranted: Boolean,
    onFiltersChanged: (ImageRequestFilters) -> Unit,
    onMapTypeChanged: (MapType) -> Unit,
    onExpandedItemsChanged: (Set<MapControlItem>) -> Unit,
    onSearchClick: () -> Unit,
    onLocationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTimePickerExpanded = MapControlItem.TimePicker in expandedItems
    val isPainting = filters.imageKind.isPainting
    val kindLabel =
        stringResource(
            if (isPainting) R.string.image_kind_paintings else R.string.image_kind_photos,
        )
    val mapTypeLabel =
        stringResource(
            if (mapType.isHybrid) R.string.map_type_satellite else R.string.map_type_scheme,
        )
    Column(modifier.fillMaxWidth()) {
        // Capped to the same width as the selector below, so the trailing bubble lands on the
        // selector's far edge instead of drifting off across a landscape screen (iOS caps the whole
        // menu at 450pt in the regular size class).
        Row(
            modifier =
                Modifier
                    .padding(horizontal = ScreenPadding)
                    .widthIn(max = MaxWidth)
                    .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlsPanel {
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
                        // Nothing else on screen says the filter is on while the picker is closed,
                        // and the accent tint alone (iOS's only cue) went unnoticed — so a
                        // notification-style dot marks it, the range left to the screen reader.
                        badged = filters.isRangeModified,
                        stateLabel =
                            "${filters.yearRange.first} - ${filters.yearRange.last}"
                                .takeIf { filters.isRangeModified },
                        isActive = filters.isRangeModified,
                    )
                    // Second in the row, as on iOS, and titled like it: the mode is named for a
                    // second right after it changes, in both directions.
                    MenuToggle(
                        checked = mapType.isHybrid,
                        onCheckedChange = { hybrid ->
                            onMapTypeChanged(if (hybrid) MapType.Hybrid else MapType.Scheme)
                        },
                        icon = if (mapType.isHybrid) Icons.Rounded.Public else Icons.Outlined.Map,
                        description = mapTypeLabel,
                        title = mapTypeLabel,
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
                        // FILL-axis swap (iOS `paintbrush.pointed` / `.fill`) is the lasting cue;
                        // the spelled-out mode only passes through on a change.
                        icon = if (isPainting) Icons.Rounded.Brush else Icons.Outlined.Brush,
                        description = kindLabel,
                        title = kindLabel,
                    )
                }
            }
            // iOS `Spacer()` between the filter items and the fixed ones. The trailing panel
            // keeps a gap of its own, so the bubbles never touch even when width runs out.
            Spacer(Modifier.weight(1f))
            ControlsPanel(Modifier.padding(start = PanelGap)) {
                Row(
                    modifier = Modifier.padding(PanelPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ControlSpacing),
                ) {
                    // Both are fixed items on iOS too (`FloatingMenuButton(item:)`), so they are
                    // plain buttons — never checked, never expanding anything. Same order as iOS:
                    // search, then location.
                    MenuButton(
                        onClick = onSearchClick,
                        icon = Icons.Rounded.Search,
                        description = stringResource(R.string.search),
                    )
                    // Momentary, not a toggle (design canon): the glyph reports whether access is
                    // there, the tap always means "take me to me" (iOS `location`/`location.slash`).
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
        // The selector unfolds by its own size rather than through `AnimatedVisibility`, and
        // without a cross-fade: the panel is opaque from the first frame and grows out of the row
        // the way iOS's expanded item does (`FloatingMenuImpl` morphs it into place via
        // `matchedGeometryEffect`). A clip-reveal is what `expandVertically` gives, and it cuts the
        // panel across — with a hairline instead of a shadow that leaves the outline hanging open
        // along a straight edge for the whole animation, which the old fade used to hide. Animating
        // the height instead means the rounded rect, and so the ring around it, is drawn whole on
        // every frame; the selector inside is revealed through it (Surface clips to its shape).
        // The spring carries iOS's `mapControlsAnimation` bounce, so it overshoots a touch instead
        // of easing flatly into place. The side inset stays out of the animated height for the same
        // reason it used to sit inside the clip: it belongs to the panel, not to the reveal.
        val unfold by
            animateFloatAsState(
                targetValue = if (isTimePickerExpanded) 1f else 0f,
                animationSpec = PanelSpring,
                label = "yearSelectorUnfold",
            )
        if (unfold > 0f) {
            ControlsPanel(
                Modifier
                    .padding(start = ScreenPadding, end = ScreenPadding)
                    .padding(top = PanelGap * unfold.coerceIn(0f, 1f))
                    .widthIn(max = MaxWidth)
                    // Both axes scale together, off the leading edge: the enclosing Column aligns
                    // Start, so a fraction of the width leaves the panel's left edge pinned at
                    // ScreenPadding while the right edge runs out to meet the search bubble above.
                    // Width can only take 0..1, so the spring's overshoot rides on the height alone
                    // — three-odd dp at the tail, past the point the two axes are read together.
                    .fillMaxWidth(unfold.coerceIn(0f, 1f))
                    .height(SelectorPanelHeight * unfold),
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

/**
 * Glass -> tonal surface: RewindGlass = `surfaceContainerHigh`, separated from the map by a hairline
 * rather than a drop shadow. A shadow is what the canon prescribes for floating chrome, but over a
 * map full of tinted pins it was one more thing competing for the eye, and it made the panels read
 * as stacked on the annotations; the 1dp `outlineVariant` ring — the same one the thumbs wear —
 * states the edge without claiming any depth.
 */
@Composable
private fun ControlsPanel(
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

/**
 * One control in the toolbar row. Port of the iOS `FloatingMenuButton` /
 * `TitledFloatingMenuButton` pair: a glyph whose pill fills with the accent while [checked], naming
 * its new state to the right for a second whenever [title] changes and then withdrawing (iOS
 * `ValueChangeIndicator(value: title, duration: 1)`). [isActive] is iOS's accent tint for a control
 * that is doing something while unchecked — the one brand accent in the chrome, which the checked
 * fill borrows rather than adding a second one.
 *
 * Sized to a 48dp pill so it nests concentrically inside the panel's 28dp corner (48/2 + 4dp panel
 * padding = 28), and it stays a pill once a title widens it. Semantics are set here rather than on
 * the glyph so a labelled control isn't announced twice — and [stateLabel] / [title] feed the state
 * description whether or not the title happens to be on screen at the time.
 */
@Composable
private fun MenuToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    stateLabel: String? = null,
    badged: Boolean = false,
    isActive: Boolean = false,
) {
    // Everything that can change while the control stays on screen animates: the fill and the glyph
    // colour cross-fade, and the label expands the pill instead of snapping its width.
    //
    // On/off is stated by an inversion, not by a tonal step: checked fills the whole pill with the
    // accent and paints the glyph in the colour the pill used to be. The tonal
    // `secondaryContainer` fill this used to wear is what M3 hands you by default, and on a panel
    // that is itself a light container it left barely a shade between a filter that is on and one
    // that is not — over a busy map, unreadable at a glance. `primary`/`onPrimary` is the stock
    // filled icon-toggle pairing, and it keeps the chrome down to the single accent it already
    // spends on [isActive] and on the badge: an accent-tinted glyph means "this filter is doing
    // something", an accent-filled pill means "and its panel is open".
    val containerColor by
        animateColorAsState(
            if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
            label = "menuToggleContainer",
        )
    val contentColor by
        animateColorAsState(
            when {
                checked -> MaterialTheme.colorScheme.onPrimary
                isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = "menuToggleContent",
        )
    // iOS `ValueChangeIndicator`: the title is shown only in response to a change — never on the
    // first composition, where SwiftUI's `onChange` would not have fired either — and a change
    // arriving while it is up restarts the second (the relaunched effect cancels the pending delay,
    // as iOS cancels its pending task). The lasting cue for a non-default state is the glyph and
    // its fill, exactly as on iOS.
    var titleShown by remember { mutableStateOf(false) }
    var isFirstTitle by remember { mutableStateOf(true) }
    LaunchedEffect(title) {
        if (isFirstTitle) {
            isFirstTitle = false
            return@LaunchedEffect
        }
        titleShown = true
        delay(TitleDuration)
        titleShown = false
    }
    // Held over so the title has something to draw while it animates back out.
    var lastTitle by remember { mutableStateOf(title) }
    if (title != null) lastTitle = title
    val state = stateLabel ?: title
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
                    // Rides the glyph's own colour, so it stays legible once the pill fills with
                    // the accent the dot would otherwise be painted in.
                    if (badged) Badge(containerColor = contentColor)
                },
            ) {
                Icon(icon, contentDescription = null)
            }
            AnimatedVisibility(
                visible = titleShown && title != null,
                // Anchored at the start so the title unrolls out of the glyph, first letter first —
                // the default end-anchor reveals its tail first, which reads as garbled text.
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                Text(
                    // The gap rides with the title so a collapsed one leaves no phantom space.
                    modifier = Modifier.padding(start = ToggleLabelGap),
                    text = lastTitle.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A menu control that just acts. Port of the iOS `FloatingMenuButton` (its `.fixed` items — search
 * and location): [MenuToggle]'s geometry and resting colours without a checked state, so a control
 * reads the same whichever bubble it sits in.
 */
@Composable
private fun MenuButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(ToggleHeight),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TogglePaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

// The panels are floating toolbars (RewindGlass): rounded, capped in width on large screens. Both
// come out 56dp tall, i.e. a true pill at a 28dp corner, and both hold their content concentrically:
// the gap around it is exactly `PanelCorner - <content corner>`, so the curves nest instead of
// drifting apart at the extremes (toggle 48/2 + 4 = 28; thumb 30/2 + 13 = 28).
private val MaxWidth = 520.dp
private val ScreenPadding = 16.dp
private val PanelCorner = 28.dp

// The one outline the chrome uses — panels and thumbs alike — instead of the canon's elevation
// level 2 (see [ControlsPanel]); nothing here carries a shadow now, so nothing needs a second one
// either. `outlineVariant` is already M3's quietest outline, and at full strength it still drew the
// eye over a busy map, so it runs at half: enough to state an edge, not enough to be read as one
// more thing on screen.
private val Hairline = 1.dp
private const val HAIRLINE_ALPHA = 0.5f
private val PanelGap = 8.dp

// The unfold's own spring, standing in for iOS `mapControlsAnimation`
// (`interactiveSpring(duration: 0.5, extraBounce: 0.1)`): slightly under-damped, so the panel
// settles with a small overshoot rather than a flat ease.
private val PanelSpring =
    spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
private val PanelPadding = 4.dp
private val ControlSpacing = 4.dp

private val ToggleHeight = 48.dp
private val TogglePaddingH = 12.dp
private val ToggleLabelGap = 6.dp

/** How long a changed state names itself before withdrawing — iOS `ValueChangeIndicator(duration: 1)`. */
private val TitleDuration = 1.seconds

// Thumb geometry echoes iOS `ThumbView` (60x30, corner 15) at a Compose scale; the year label sits
// centred. The track thickness echoes iOS `lineHeight` (7), the selector height iOS's `frame(50)`.
private val SelectorHeight = 50.dp
private val SelectorPaddingH = 13.dp
private val SelectorPaddingV = 3.dp
private val ThumbWidth = 54.dp
private val ThumbHeight = 30.dp
private val ThumbCorner = 15.dp
private val TrackThickness = 8.dp

// The selector panel's settled height, which the unfold animates towards. Deterministic (the
// selector is a fixed-height row), so the panel can own its height instead of being clip-revealed —
// and it lands on the same 56dp pill as the toolbar panels.
private val SelectorPanelHeight = SelectorHeight + SelectorPaddingV * 2
