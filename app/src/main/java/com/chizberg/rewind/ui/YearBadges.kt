package com.chizberg.rewind.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.domain.Direction
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageDate

/** iOS `ColoredContainer`: radius 10, padding 7 — shared by both badges so they read as a pair. */
private val BadgeCorner = 10.dp
private val BadgePadding = 7.dp

/** iOS draws an 8×10 `arrowtriangle.up.fill`; the box around it leaves room for the 45° rhumbs. */
private val ArrowWidth = 8.dp
private val ArrowHeight = 10.dp
private val ArrowBox = 14.dp

/** iOS `HStack(spacing: 5)` between the rhumb label and its arrow. */
private val ArrowSpacing = 5.dp

/**
 * The year-tinted date badge of an image. Port of iOS `ImageDateView`.
 *
 * iOS reads the scheme and the range from the environment; here they are passed down, as everywhere
 * else in this port (the tint layer is explicit, not ambient).
 */
@Composable
fun ImageDateBadge(
    date: ImageDate,
    scheme: GradientScheme,
    maxRange: IntRange,
    modifier: Modifier = Modifier,
) {
    ColoredContainer(date.year, scheme, maxRange, modifier) {
        Text(
            text = date.description,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The shooting-direction badge. Port of iOS `DirectionView`: the same year-tinted container as
 * [ImageDateBadge], carrying the rhumb in bold monospace plus a triangle rotated to the bearing —
 * not a bare compass icon on the surface colour.
 */
@Composable
fun DirectionBadge(
    date: ImageDate,
    direction: Direction,
    scheme: GradientScheme,
    maxRange: IntRange,
    modifier: Modifier = Modifier,
) {
    ColoredContainer(date.year, scheme, maxRange, modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArrowSpacing),
        ) {
            Text(
                text = direction.name,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            // `aero` (top-down) has no bearing: iOS leaves the arrow pointing up.
            UpArrow(Modifier.size(ArrowBox).rotate(direction.angleDegrees ?: 0f))
        }
    }
}

@Composable
private fun ColoredContainer(
    year: Int,
    scheme: GradientScheme,
    maxRange: IntRange,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tint = scheme.color(year, maxRange)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(BadgeCorner),
        color = tint.toComposeColor(),
        contentColor = scheme.foreground(tint).toComposeColor(),
    ) {
        Box(Modifier.padding(BadgePadding)) { content() }
    }
}

/** A filled triangle pointing up, centred in its box so a rotated rhumb never clips. */
@Composable
private fun UpArrow(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val width = ArrowWidth.toPx()
        val height = ArrowHeight.toPx()
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        val triangle =
            Path().apply {
                moveTo(left + width / 2f, top)
                lineTo(left + width, top + height)
                lineTo(left, top + height)
                close()
            }
        drawPath(triangle, color)
    }
}
