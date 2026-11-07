package com.chizberg.rewind.features.imagelist.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.toBitmap
import com.chizberg.rewind.R
import com.chizberg.rewind.colorization.image.Lab
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.imagelist.ImageCardTitleStyle
import com.chizberg.rewind.features.map.ui.RewindAsyncImage
import com.chizberg.rewind.ui.ImageDateBadge
import com.chizberg.rewind.ui.toArgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Composable
fun ImageGrid(
    images: List<ModelImage>,
    scheme: GradientScheme,
    maxRange: IntRange,
    onImageClick: (ModelImage) -> Unit,
    cardBackground: () -> Color,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(),
    padding: Dp = 16.dp,
) {
    BoxWithConstraints(modifier) {
        val direction = LocalLayoutDirection.current
        val start = contentPadding.calculateStartPadding(direction) + padding
        val end = contentPadding.calculateEndPadding(direction) + padding
        val fitting = ceil((maxWidth - start - end + Spacing) / (MaxCardWidth + Spacing)).toInt()
        val columnCount = max(MIN_COLUMNS, fitting)
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columnCount),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = start,
                    end = end,
                    top = contentPadding.calculateTopPadding() + padding - Spacing,
                    bottom = contentPadding.calculateBottomPadding() + padding,
                ),
            verticalItemSpacing = Spacing,
            horizontalArrangement = Arrangement.spacedBy(Spacing),
        ) {
            repeat(columnCount) { column ->
                item(key = "shift-$column", contentType = "shift") {
                    val isShifted = (columnCount - column) % 2 != 0
                    Spacer(Modifier.height(if (isShifted) ColumnShift else 0.dp))
                }
            }
            items(images, key = { it.cid }, contentType = { "card" }) { image ->
                ImageCard(
                    image = image,
                    scheme = scheme,
                    maxRange = maxRange,
                    background = cardBackground,
                    onClick = { onImageClick(image) },
                    modifier = Modifier.animateItem().edgeScale(state, image.cid),
                )
            }
        }
    }
}

private fun Modifier.edgeScale(
    state: LazyStaggeredGridState,
    key: Any,
): Modifier =
    graphicsLayer {
        val layout = state.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return@graphicsLayer
        val height = item.size.height
        if (height == 0) return@graphicsLayer
        val top = item.offset.y
        val outside =
            max(layout.viewportStartOffset - top, top + height - layout.viewportEndOffset)
        val scale = 1 - EDGE_SCALE * (outside.coerceAtLeast(0).toFloat() / height).coerceAtMost(1f)
        scaleX = scale
        scaleY = scale
    }

@Composable
fun ImageCard(
    image: ModelImage,
    scheme: GradientScheme,
    maxRange: IntRange,
    background: () -> Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var titleStyle by remember(image.cid) { mutableStateOf<ImageCardTitleStyle?>(null) }
    var loaded by remember(image.cid) { mutableStateOf<Bitmap?>(null) }
    var titleHeight by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(loaded) {
        val bitmap = loaded ?: return@LaunchedEffect
        titleStyle = withContext(Dispatchers.Default) { bitmap.titleStyle() }
    }
    val dimming by animateFloatAsState((titleStyle as? ImageCardTitleStyle.Dimmed)?.opacity ?: 0f)
    val titleColor by animateColorAsState(
        when (titleStyle) {
            null -> MaterialTheme.colorScheme.onSurface
            ImageCardTitleStyle.Dark -> DarkTitle
            else -> Color.White
        },
    )
    val shape = RoundedCornerShape(CardRadius)
    Box(
        modifier
            .aspectRatio(CARD_ASPECT)
            .clip(shape)
            .drawWithContent {
                drawRect(background())
                drawContent()
            }.clickable(onClick = onClick)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = BORDER_ALPHA)), shape),
    ) {
        var isLoading by remember(image.cid) { mutableStateOf(true) }
        RewindAsyncImage(
            path = image.imagePath,
            contentDescription = image.title,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                isLoading = state is AsyncImagePainter.State.Loading
                if (state is AsyncImagePainter.State.Success) loaded = state.result.image.toBitmap()
            },
        )
        if (!isLoading && titleHeight > 0f) {
            RewindAsyncImage(
                path = image.imagePath,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                fadeBrush(Color.Black, titleHeight * FADE_EXTENT),
                                blendMode = BlendMode.DstIn,
                            )
                        }.graphicsLayer {
                            val radius = TitleBlur.toPx()
                            renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                        },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { titleHeight = it.height.toFloat() }
                .drawBehind {
                    val height = size.height * FADE_EXTENT
                    drawRect(
                        fadeBrush(Color.Black.copy(alpha = dimming), height),
                        size = size.copy(height = height),
                    )
                }.padding(CardRadius - 10.dp),
        ) {
            Text(
                text = image.title,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
            )
        }
        ImageDateBadge(
            date = image.date,
            scheme = scheme,
            maxRange = maxRange,
            modifier = Modifier.align(Alignment.BottomStart).padding(CardRadius - BadgeRadius),
        )
        if (isLoading) DelayedProgress(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun DelayedProgress(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ProgressDelay)
        visible = true
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        CircularProgressIndicator(
            Modifier.size(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            strokeWidth = 2.dp,
        )
    }
}

class EmptyListPlaceholder(
    val emoji: String,
    @StringRes val title: Int,
) {
    companion object {
        val general =
            listOf(
                EmptyListPlaceholder("👀", R.string.list_empty),
                EmptyListPlaceholder("🔭", R.string.placeholder_nothing_in_sight),
                EmptyListPlaceholder("🍃", R.string.placeholder_all_quiet),
                EmptyListPlaceholder("📭", R.string.placeholder_empty_for_now),
                EmptyListPlaceholder("🧭", R.string.placeholder_nothing_found),
                EmptyListPlaceholder("🐾", R.string.placeholder_no_traces),
                EmptyListPlaceholder("🪴", R.string.placeholder_nothing_to_show),
            )
        val map =
            general + EmptyListPlaceholder("🕰️", R.string.placeholder_nothing_from_this_time)
    }
}

@Composable
fun EmptyList(
    modifier: Modifier = Modifier,
    placeholders: List<EmptyListPlaceholder> = EmptyListPlaceholder.general,
    placeholderChange: Int = 0,
    @StringRes hint: Int? = null,
) {
    var emoji by rememberSaveable { mutableStateOf(placeholders.random().emoji) }
    var lastChange by rememberSaveable { mutableStateOf(placeholderChange) }
    if (placeholderChange != lastChange) {
        lastChange = placeholderChange
        emoji = placeholders.filter { it.emoji != emoji }.random().emoji
    }
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedContent(
            targetState = EmptyListPlaceholder.map.first { it.emoji == emoji },
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith
                    (fadeOut() + scaleOut(targetScale = 0.9f)) using
                    SizeTransform(clip = false)
            },
        ) { placeholder ->
            val blur by transition.animateDp { if (it == EnterExitState.Visible) 0.dp else 10.dp }
            Column(
                modifier = Modifier.blur(blur, BlurredEdgeTreatment.Unbounded),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(placeholder.emoji, fontSize = 56.sp)
                Text(
                    text = stringResource(placeholder.title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AnimatedContent(
            targetState = hint,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
        ) { hint ->
            if (hint != null) {
                Text(
                    text = stringResource(hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal fun fadeBrush(
    color: Color,
    height: Float,
): Brush =
    Brush.verticalGradient(
        List(FADE_STOPS + 1) { i ->
            val fade = ((i.toFloat() / FADE_STOPS - FADE_HOLD) / (1 - FADE_HOLD)).coerceIn(0f, 1f)
            color.copy(alpha = color.alpha * (1 - fade * fade * (3 - 2 * fade)))
        },
        endY = height,
    )

private fun Bitmap.titleStyle(): ImageCardTitleStyle {
    val source =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val visibleWidth: Int
    val visibleHeight: Int
    if (source.width.toFloat() / source.height > CARD_ASPECT) {
        visibleHeight = source.height
        visibleWidth = (source.height * CARD_ASPECT).roundToInt()
    } else {
        visibleWidth = source.width
        visibleHeight = (source.width / CARD_ASPECT).roundToInt()
    }
    val left = (source.width - visibleWidth) / 2
    val top = (source.height - visibleHeight) / 2
    val captionHeight = (visibleHeight * CAPTION_FRACTION).roundToInt().coerceAtLeast(1)
    val sampleHeight = max(1, (SAMPLE_WIDTH.toFloat() * captionHeight / visibleWidth).roundToInt())
    val caption = Bitmap.createBitmap(source, left, top, visibleWidth, captionHeight)
    val sample = Bitmap.createScaledBitmap(caption, SAMPLE_WIDTH, sampleHeight, true)
    val pixels = sample.toArgbImage().pixels
    val luminances =
        FloatArray(pixels.size) { i ->
            val pixel = pixels[i]
            Lab.luminance(
                ((pixel shr 16) and 0xFF) / 255f,
                ((pixel shr 8) and 0xFF) / 255f,
                (pixel and 0xFF) / 255f,
            )
        }
    return ImageCardTitleStyle.of(luminances)
}

private const val CARD_ASPECT = 3f / 4f
private const val CAPTION_FRACTION = 0.3f
private const val SAMPLE_WIDTH = 24
private const val BORDER_ALPHA = 0.2f
private const val FADE_STOPS = 16
private const val FADE_HOLD = 0.35f
private const val FADE_EXTENT = 1.4f
private const val EDGE_SCALE = 0.1f
private const val MIN_COLUMNS = 2
private val Spacing = 10.dp
private val ColumnShift = 60.dp
private val MaxCardWidth = 250.dp
private val CardRadius = 25.dp
private val BadgeRadius = 10.dp
private val TitleBlur = 35.dp
private val DarkTitle = Color(0xFF262626)
private val ProgressDelay = 0.5.seconds
