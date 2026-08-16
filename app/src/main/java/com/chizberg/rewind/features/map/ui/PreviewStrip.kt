package com.chizberg.rewind.features.map.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.R
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.features.map.PreviewCard
import com.chizberg.rewind.ui.toComposeColor

/** The bottom preview strip: a full-width bottom sheet of thumbnail cards over the map. Port of iOS
 * `MapControls` content (its `AutoscrollingScrollView` of `ThumbnailCardView`s). The sheet bleeds to
 * the bottom screen edge — where the device's own screen corner clips it, so its bottom corners are
 * concentric with the display for free on every device — and only its top corners are rounded. Its
 * background extends behind the navigation bar while the cards are inset above the home indicator.
 * A fixed leading column carries the favorites (star), view-as-list and settings buttons (iOS
 * `MapControls.makeBottomScrollButton`). The strip stays a plain view: the gesture layer that folds
 * it away (drag-to-minimize, the glimpse it leaves, the auto-minimize under a map drag) lives in
 * `RewindMap`, as iOS keeps it in the parent `MapControls`. [contentAlpha] is that layer's dimming
 * of the folded strip — iOS's `content.opacity(0.5)`, applied to the cards and not to the sheet, so
 * the sheet itself stays opaque. Of the iOS drivers only pull-up-to-open-the-list is not ported: its
 * job is done by the trailing "view as list" card, which iOS shows as well.
 * Cards are tinted by year via [scheme] over [maxRange] (mirrors the map annotations). */
@Composable
fun PreviewStrip(
    previews: List<PreviewCard>,
    isLoading: Boolean,
    scheme: GradientScheme,
    maxRange: IntRange,
    onCardClick: (PreviewCard) -> Unit,
    onFavoritesClick: () -> Unit,
    onViewAsListClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlpha: () -> Float = { 1f },
) {
    Surface(
        // Sideways the sheet stays inside the safe area — in landscape the window now reaches under
        // the cutout and the side navigation bar, and a sheet running under either would put cards
        // where they can't be seen or tapped. Vertically it still bleeds to the bottom screen edge.
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .fillMaxWidth(),
        shape = RoundedCornerShape(topStart = TopCorner, topEnd = TopCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 6.dp,
    ) {
        // Background bleeds behind the nav bar; the cards are lifted above the home indicator. Fixed
        // height so the strip keeps the card's footprint even when there are no cards yet (empty
        // previews on first load) — it must not collapse before the first data lands.
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(StripContentHeight)
                // Read in the draw phase, not composition: the fold animates it every frame, and
                // the strip must not recompose (cards, LazyRow and all) on each of them.
                .graphicsLayer { alpha = contentAlpha() },
        ) {
            val listState = rememberLazyListState()
            // Mirror iOS AutoscrollingScrollView: snap back to the first card whenever the set changes.
            LaunchedEffect(previews) { listState.animateScrollToItem(0) }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(CardSpacing),
                contentPadding =
                    PaddingValues(
                        start = StripPadding,
                        top = StripPadding,
                        end = StripPadding,
                        bottom = StripBottomPadding,
                    ),
            ) {
                item(key = "controls") {
                    StripControls(
                        onFavoritesClick = onFavoritesClick,
                        onViewAsListClick = onViewAsListClick,
                        onSettingsClick = onSettingsClick,
                        modifier = Modifier.height(CardHeight),
                    )
                }
                items(previews, key = { it.id }) { card ->
                    ThumbnailCard(
                        card = card,
                        scheme = scheme,
                        maxRange = maxRange,
                        onClick = { onCardClick(card) },
                        // Fade in/out on add/remove and spring to new positions when the set changes
                        // (mirrors iOS `.animation(.spring, value: previews)` + card transitions).
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(StripPadding)
                            .size(ProgressSize),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * The strip's fixed leading controls: favorites (star), view-as-list and settings, stacked in that
 * order. Port of the iOS `MapControls` leading button column (`star` / `list.bullet` / `gearshape`,
 * same order). Sized to the card's footprint so it reads as part of the row.
 */
@Composable
private fun StripControls(
    onFavoritesClick: () -> Unit,
    onViewAsListClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(ControlWidth),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        StripControlButton(
            icon = Icons.Rounded.Star,
            contentDescription = stringResource(R.string.list_favorites),
            onClick = onFavoritesClick,
            modifier = Modifier.weight(1f),
        )
        StripControlButton(
            icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
            contentDescription = stringResource(R.string.preview_view_as_list),
            onClick = onViewAsListClick,
            modifier = Modifier.weight(1f),
        )
        StripControlButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.settings),
            onClick = onSettingsClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StripControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(IconSize))
        }
    }
}

/** One card in the strip: a tinted thumbnail with a year badge, or a text placeholder. */
@Composable
private fun ThumbnailCard(
    card: PreviewCard,
    scheme: GradientScheme,
    maxRange: IntRange,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = CardWidth, height = CardHeight),
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        when (card) {
            is PreviewCard.Image -> ImageCardContent(card, scheme, maxRange)
            PreviewCard.NoImages ->
                PlaceholderContent(label = R.string.preview_no_images, emoji = "👀")
            PreviewCard.ViewAsList ->
                PlaceholderContent(
                    label = R.string.preview_view_as_list,
                    icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                )
        }
    }
}

@Composable
private fun ImageCardContent(
    card: PreviewCard.Image,
    scheme: GradientScheme,
    maxRange: IntRange,
) {
    val image = card.value
    val tint = scheme.color(image.date.year, maxRange)
    Box(Modifier.fillMaxSize()) {
        RewindAsyncImage(
            path = image.imagePath,
            contentDescription = image.title,
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(BadgeInset),
            // Canon fixes the date badge at 10dp (not the M3 `small` 8dp); the theme-level shape
            // vocabulary lands with the design foundation, so pin it explicitly here.
            shape = RoundedCornerShape(BadgeCorner),
            color = tint.toComposeColor(),
        ) {
            Text(
                text = image.date.description,
                modifier = Modifier.padding(horizontal = BadgePaddingH, vertical = BadgePaddingV),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.foreground(tint).toComposeColor(),
            )
        }
    }
}

@Composable
private fun PlaceholderContent(
    @StringRes label: Int,
    emoji: String? = null,
    icon: ImageVector? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                emoji != null -> Text(emoji, style = MaterialTheme.typography.headlineMedium)
                icon != null ->
                    Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize))
            }
            Text(
                stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Matches iOS `thumbnailSize` (250 x 187.5, 4:3) — the strip cards read a touch larger than the
// earlier 200 x 150 and now line up with the iOS preview strip side by side.
private val CardWidth = 250.dp
private val CardHeight = 187.5.dp
private val CardSpacing = 8.dp

// The leading control column's width — narrower than a card since it only carries stacked icons.
private val ControlWidth = 56.dp

// Padding around the cards inside the strip (iOS `glassCardPadding`); the bottom gap is a touch
// smaller since the sheet already floats above the home indicator.
private val StripPadding = 20.dp
private val StripBottomPadding = 10.dp

// The strip's content height, held constant so an empty preview list doesn't collapse it.
private val StripContentHeight = CardHeight + StripPadding + StripBottomPadding
private val ProgressSize = 24.dp
private val IconSize = 32.dp

// Card corner (iOS `mapControlRadius`) and the year badge corner (iOS `ImageDateView` radius).
private val CardCorner = 25.dp
private val BadgeCorner = 10.dp

// The badge is inset from the card's bottom-left by (card corner − badge corner) so the two corner
// arcs share a center and nest concentrically — the iOS ThumbnailView `padding(radius - cardRadius)`.
private val BadgeInset = CardCorner - BadgeCorner
private val BadgePaddingH = 8.dp
private val BadgePaddingV = 3.dp

// The sheet's top corner radius (the bottom corners are the device's own, clipped by the display).
private val TopCorner = 35.dp
