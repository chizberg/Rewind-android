package com.chizberg.rewind.features.imagelist.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.R
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageSorting
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.details.ui.ImageDetailsView
import com.chizberg.rewind.features.imagelist.ImageListAction
import com.chizberg.rewind.features.imagelist.ImageListModel
import com.chizberg.rewind.features.imagelist.ImageListState
import com.chizberg.rewind.features.map.ui.RewindAsyncImage
import com.chizberg.rewind.ui.ImageDateBadge
import com.chizberg.rewind.ui.Overlay

/** iOS `LazyVGrid(.adaptive(minimum: 300))`. */
private val GridMinCell = 300.dp

/** iOS `LazyVGrid` spacing 10 (both axes) and its fixed `.padding(.horizontal, 16)`. */
private val GridSpacing = 10.dp
private val GridInset = 16.dp

/** iOS `ImageListCell` corner radius (20) and its `.padding(radius)` text inset. */
private val CellCorner = 20.dp
private val CellPadding = 20.dp
private const val CELL_ASPECT = 4f / 3f

/** iOS `ImageListCell` scrim: clear → black 0.5, top-to-bottom. */
private const val SCRIM_ALPHA = 0.5f

/**
 * The image-grid screen. Port of iOS `ImageList` + `ImageListCell`. Renders one [ImageListModel]:
 * a large collapsing title, an optional sort menu (hidden when `sorting == null`, i.e. Favorites),
 * an adaptive grid of 4:3 cells, and — as a nested layer — the details screen a cell tap opens
 * (its own overlay, so back closes it before the list; iOS `ImageListModel.imageDetails`). An empty
 * list shows the 💔 placeholder.
 *
 * Divergences from the iOS view: no shared-element zoom transition (details open through the plain
 * [Overlay], as everywhere in this port); the iOS Android-idiomatic long-press-to-unfavorite
 * with an undo snackbar (a design-phase proposal) is deliberately NOT here — it would add a
 * non-iOS `restore` action to `FavoritesModel`; the star in details remains the removal path.
 */
@Composable
fun ImageListView(
    model: ImageListModel,
    scheme: GradientScheme,
    maxRange: IntRange,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    // Just the list surface plus a declaration of the details layer it opens. The list is itself an
    // overlay layer (the map recedes behind it), and when a cell opens its details this whole surface
    // recedes behind them in turn — all handled by [OverlayHost], so there is nothing to wire here
    // beyond declaring the child [Overlay]. LIFO back still closes details before the list.
    ListScaffold(
        state = state,
        scheme = scheme,
        maxRange = maxRange,
        onDismiss = onDismiss,
        onImageClick = { model(ImageListAction.PresentImage(it)) },
        onSelectSorting = { model(ImageListAction.SetSorting(it)) },
        modifier = modifier,
    )

    Overlay(
        target = state.imageDetails,
        onBack = { model(ImageListAction.DismissImage) },
    ) { details ->
        ImageDetailsView(
            model = details,
            scheme = scheme,
            maxRange = maxRange,
            onDismiss = { model(ImageListAction.DismissImage) },
        )
    }
}

/** The list surface itself: collapsing title, optional sort menu, and the adaptive grid (or the
 *  empty placeholder). Rendered by [ImageListView] as its overlay layer's content — the overlay host
 *  recedes it when the cell details open over it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScaffold(
    state: ImageListState,
    scheme: GradientScheme,
    maxRange: IntRange,
    onDismiss: () -> Unit,
    onImageClick: (ModelImage) -> Unit,
    onSelectSorting: (ImageSorting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()

    // iOS `.onChange(of: sorting) { scrollPosition.scrollTo(.top) }`: a re-sort jumps back up.
    LaunchedEffect(state.sorting) { gridState.animateScrollToItem(0) }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    // Only the weight is set here, never the size: LargeTopAppBar interpolates the
                    // title's font size (expanded headlineMedium -> collapsed titleLarge) through
                    // LocalTextStyle as it scrolls, and an explicit fontWeight merges over that
                    // without pinning the size — so the title reads bolder in both states while the
                    // collapse animation stays intact. (Bigger expanded type / a subtitle would need
                    // the flexible top app bar, which is material3 1.5.0-alpha only.)
                    Text(
                        text = stringResource(state.title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    state.sorting?.let { current ->
                        SortMenu(current = current, onSelect = onSelectSorting)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.images.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
        } else {
            // The grid runs edge-to-edge and applies the Scaffold insets once, through
            // contentPadding (top app-bar height + bottom nav-bar), so it scrolls under a
            // transparent nav bar instead of being clipped above it. Horizontal inset is fixed at
            // 16dp (iOS parity), not a system inset.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(GridMinCell),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = GridInset,
                        end = GridInset,
                        top = padding.calculateTopPadding() + GridSpacing,
                        bottom = padding.calculateBottomPadding() + GridSpacing,
                    ),
                horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                verticalArrangement = Arrangement.spacedBy(GridSpacing),
            ) {
                items(state.images, key = { it.cid }) { image ->
                    ImageListCell(
                        image = image,
                        scheme = scheme,
                        maxRange = maxRange,
                        onClick = { onImageClick(image) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * One grid cell. Port of iOS `ImageListCell`: a 4:3 thumbnail with a bottom scrim, a white
 * two-line title, and the year-tinted date badge. The chrome (title, scrim) is plain white/black —
 * only the date badge is year-tinted (as on iOS, via `ImageDateView`), unlike the strip card.
 */
@Composable
private fun ImageListCell(
    image: ModelImage,
    scheme: GradientScheme,
    maxRange: IntRange,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(CELL_ASPECT)
            .clip(RoundedCornerShape(CellCorner))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        RewindAsyncImage(
            path = image.imagePath,
            contentDescription = image.title,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = SCRIM_ALPHA)),
                        ),
                    ).padding(CellPadding),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = image.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            ImageDateBadge(date = image.date, scheme = scheme, maxRange = maxRange)
        }
    }
}

/** iOS empty state: 💔 over "Nothing here yet". */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("💔", style = MaterialTheme.typography.displaySmall)
            Text(
                text = stringResource(R.string.list_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The sort menu. Port of iOS's toolbar `Menu` + `Picker`: a swap-vertical button opening a dropdown
 * of the three sortings (order is `ImageSorting` declaration order = iOS `allCases`), the active one
 * check-marked.
 */
@Composable
private fun SortMenu(
    current: ImageSorting,
    onSelect: (ImageSorting) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.sorting))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ImageSorting.entries.forEach { sorting ->
                DropdownMenuItem(
                    text = { Text(stringResource(sorting.labelRes)) },
                    onClick = {
                        onSelect(sorting)
                        expanded = false
                    },
                    leadingIcon = { Icon(sorting.icon, contentDescription = null) },
                    trailingIcon =
                        if (sorting == current) {
                            { Icon(Icons.Rounded.Check, contentDescription = null) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@get:StringRes
private val ImageSorting.labelRes: Int
    get() =
        when (this) {
            ImageSorting.DateAscending -> R.string.sort_date_ascending
            ImageSorting.DateDescending -> R.string.sort_date_descending
            ImageSorting.Shuffle -> R.string.sort_shuffle
        }

/** iOS `arrow.up` / `arrow.down` / `shuffle`. */
private val ImageSorting.icon: ImageVector
    get() =
        when (this) {
            ImageSorting.DateAscending -> Icons.Rounded.ArrowUpward
            ImageSorting.DateDescending -> Icons.Rounded.ArrowDownward
            ImageSorting.Shuffle -> Icons.Rounded.Shuffle
        }
