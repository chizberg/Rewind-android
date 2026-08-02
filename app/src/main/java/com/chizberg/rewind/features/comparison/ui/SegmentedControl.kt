package com.chizberg.rewind.features.comparison.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The amber of a selected segment — iOS paints it `.yellow`, and the same amber is the favorite
 * star on the details screen. Spelled out rather than derived: there is no extended-colour layer in
 * this port, and the two consumers have to agree, so they share the literal (the design pack's
 * "identical by construction", by construction of a different kind).
 */
private val SelectedAmber = Color(0xFFE0B32E)

/** iOS `.primary.opacity(0.7)` on the segments that are not selected. */
private const val INACTIVE_ALPHA = 0.7f

/**
 * The style / lens picker of the comparison screen. Port of iOS `CustomSegmentedControl` — a
 * generic row where the caller draws each item and knows nothing about selection chrome.
 *
 * Nativized, per the design pack: M3's [SingleChoiceSegmentedButtonRow] supplies the selection,
 * which drops iOS's sliding `matchedGeometryEffect` capsule (a deliberate loss — the stock
 * component's selected fill says the same thing) and keeps the amber it says it with.
 */
@Composable
fun <T> SegmentedControl(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (item: T, isSelected: Boolean) -> Unit,
) {
    val colors =
        SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            activeContentColor = SelectedAmber,
            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            inactiveContentColor =
                MaterialTheme.colorScheme.onSurface.copy(alpha = INACTIVE_ALPHA),
            activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
            inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
        )
    SingleChoiceSegmentedButtonRow(modifier) {
        items.forEachIndexed { index, item ->
            val isSelected = item == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = items.size),
                colors = colors,
                // The stock check mark would push the glyph aside; the fill is the selection here,
                // as it is on iOS.
                icon = {},
                label = { content(item, isSelected) },
            )
        }
    }
}

/** A square step, sized like the touch target it is rather than like a segment: this control floats
 *  over imagery instead of sitting in the row of pickers. */
private val StepSize = 48.dp

/** Enough of a fade to read as unavailable without the glyph disappearing over the panorama. */
private const val DISABLED_ALPHA = 0.3f

/**
 * A stack of momentary buttons wearing [SegmentedControl]'s clothes — the panorama's zoom, which is
 * not a choice between states and so cannot be a segmented button (a screen reader would announce
 * radio buttons that never stay picked).
 *
 * Vertical, and placed over the viewfinder rather than in the picker row: the thing it changes is
 * the panorama right behind it, and a camera's zoom is where the eye already looks for it.
 *
 * One `Surface` with dividers rather than one per step: adjacent bordered surfaces would draw the
 * seam twice, which the stock segmented row avoids with overlaps of its own.
 */
@Composable
fun StepperControl(
    steps: List<StepperStep>,
    modifier: Modifier = Modifier,
    glyphModifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(StepSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border =
            BorderStroke(
                SegmentedButtonDefaults.BorderWidth,
                MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            steps.forEachIndexed { index, step ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Box(
                    modifier =
                        Modifier
                            .height(StepSize)
                            .fillMaxWidth()
                            .clickable(enabled = step.enabled, onClick = step.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = step.icon,
                        contentDescription = step.contentDescription,
                        modifier = glyphModifier,
                        tint =
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (step.enabled) INACTIVE_ALPHA else DISABLED_ALPHA,
                            ),
                    )
                }
            }
        }
    }
}

/** One button of a [StepperControl]. */
data class StepperStep(
    val icon: ImageVector,
    val contentDescription: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)
