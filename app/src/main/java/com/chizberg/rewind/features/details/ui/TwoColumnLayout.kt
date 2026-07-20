package com.chizberg.rewind.features.details.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The action grid. Port of iOS `TwoColumnLayout`: two equal columns, except that an item too wide
 * for a column takes a full-width row of its own — and if it is the *right* item of a pair, its
 * partner is pushed onto a full-width row too, so a pair never breaks into a half-row plus a wrap.
 * A lone trailing item is always full width.
 *
 * A plain `chunked(2)` grid can't do this: in the narrow metadata column of the split layout, every
 * label ("Show on map", "View on Web") is wider than half the column and would be clipped instead
 * of unfolding.
 */
@Composable
fun TwoColumnLayout(
    modifier: Modifier = Modifier,
    columnSpacing: Dp = 8.dp,
    rowSpacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = columnSpacing.roundToPx()
        val rowGap = rowSpacing.roundToPx()
        val columnWidth = (width - gap) / 2

        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        val ys = IntArray(measurables.size)

        fun IntrinsicMeasurable.isWide() = maxIntrinsicWidth(Constraints.Infinity) > columnWidth

        var y = 0
        var index = 0

        fun startRow(): Int = if (y == 0) 0 else y + rowGap

        fun placeFullWidth(item: Int) {
            val placeable = measurables[item].measure(Constraints.fixedWidth(width))
            placeables[item] = placeable
            xs[item] = 0
            ys[item] = startRow()
            y = ys[item] + placeable.height
        }

        // A regular row: both items get the taller one's height, so the pair reads as one row.
        fun placePair(
            left: Int,
            right: Int,
        ) {
            val rowHeight =
                maxOf(
                    measurables[left].minIntrinsicHeight(columnWidth),
                    measurables[right].minIntrinsicHeight(columnWidth),
                )
            val rowConstraints = Constraints.fixed(columnWidth, rowHeight)
            val rowY = startRow()
            placeables[left] = measurables[left].measure(rowConstraints)
            xs[left] = 0
            ys[left] = rowY
            placeables[right] = measurables[right].measure(rowConstraints)
            xs[right] = columnWidth + gap
            ys[right] = rowY
            y = rowY + rowHeight
        }

        while (index < measurables.size) {
            val left = index
            val right = index + 1
            val lone = right > measurables.lastIndex
            index +=
                when {
                    lone || measurables[left].isWide() -> {
                        placeFullWidth(left)
                        1
                    }

                    measurables[right].isWide() -> {
                        placeFullWidth(left)
                        placeFullWidth(right)
                        2
                    }

                    else -> {
                        placePair(left, right)
                        2
                    }
                }
        }

        layout(width, y) {
            placeables.forEachIndexed { i, placeable -> placeable?.place(xs[i], ys[i]) }
        }
    }
}
