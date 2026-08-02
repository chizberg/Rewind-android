package com.chizberg.rewind.features.comparison.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import com.chizberg.rewind.app.CapturedBitmap
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.comparison.ComparisonState
import com.chizberg.rewind.features.map.ui.RewindAsyncImage
import com.chizberg.rewind.network.ImageQuality
import java.time.LocalDate

/** iOS `CardOnCardView.scale` / `.radius`, and its `3/4` fallback aspect for the cards. */
private const val CARD_SCALE = 0.6f
private val CARD_RADIUS = 10.dp
private const val DEFAULT_OLD_ASPECT = 3f / 4f

/**
 * iOS renders the divider row at a flat `.system(size: 11)` — a *whole* font, not just a size. The
 * style has to be spelled out here because `Text` merges with `LocalTextStyle`, and overriding only
 * the size leaves `bodyLarge`'s 24sp line height and 0.5sp tracking behind, which turns an 11pt
 * seam into a band twice its height. Trimming the line height's leading is what leaves the row as
 * tight around the glyphs as SwiftUI's is.
 */
private val DividerTextStyle =
    TextStyle(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.sp,
        lineHeightStyle =
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
    )

/** SF Symbols scale with the font; a Material glyph has to be told, and its chevron sits in a
 *  roomier box than `chevron.up` does. */
private val DividerIconSize = 12.dp

/** iOS `HStack`'s default spacing, which the divider row relies on. */
private val DividerSpacing = 8.dp

/** iOS `.padding(10)` inside each auto-sizing corner label. */
private val CornerLabelPadding = 10.dp

/** iOS `BlinkingModifier`: black in on the shot, eased back out over a quarter second. */
private const val BLINK_MS = 250

/** The wordmark iOS burns into every composite. */
private const val WORDMARK = "Rewind <<"

/**
 * The comparison canvas — the part that is saved and shared, and nothing else. Port of iOS
 * `ComparisonView`: the old photo and the new frame in one of two arrangements, each labelled with
 * its year, blinking whenever a shot is taken.
 *
 * The "now" year is the Street View panorama's own year when there is one, and today's year
 * otherwise (iOS `streetViewYear ?? currentYear`) — a 2011 panorama must not be labelled 2026.
 *
 * [viewfinder] is the live half: the camera preview or the panorama, composed by the screen (see
 * `Viewfinder.kt`). It is replaced by the taken frame the moment there is one, which is also why
 * the canvas can be rendered out of Compose alone — no `SurfaceView` is in it when it is captured.
 *
 * [recorder] is what turns the arrangement into the saved image, and it goes on **the arrangement,
 * not the 4:6 frame around it**. On iOS the frame is a `Color.clear` the view is drawn over, while
 * the thing rendered into a file is the view's own ideal size: side by side that is the whole
 * frame, card on card only the rectangle the cards need. Recording the frame instead padded every
 * card-on-card composite with a quarter of black at each end and left the wordmark stranded at the
 * top of the screen rather than at the corner of the cards.
 */
@Composable
fun ComparisonView(
    style: ComparisonState.Style,
    oldImage: ModelImage,
    captureState: ComparisonState.CaptureState?,
    streetViewYear: Int?,
    shotsCount: Int,
    viewfinder: @Composable (Modifier) -> Unit,
    recorder: Modifier,
    modifier: Modifier = Modifier,
) {
    val currentYear = remember { LocalDate.now().year }
    // iOS takes the card aspect from the decoded old image; ours learns it from Coil when the
    // photo lands (the reducer holds no bitmap), until then the same 3:4 iOS falls back to.
    var oldAspect by remember(oldImage.cid) { mutableFloatStateOf(DEFAULT_OLD_ASPECT) }

    val old: @Composable (Modifier) -> Unit = { innerModifier ->
        RewindAsyncImage(
            path = oldImage.imagePath,
            contentDescription = oldImage.title,
            modifier = innerModifier,
            quality = ImageQuality.High,
            contentScale = ContentScale.Crop,
            placeholderQuality = ImageQuality.Medium,
            onState = { state ->
                (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize?.let { size ->
                    if (size.width > 0f && size.height > 0f) oldAspect = size.width / size.height
                }
            },
        )
    }
    val new: @Composable (Modifier) -> Unit = { innerModifier ->
        NewFrame(captureState, viewfinder, innerModifier)
    }

    // The blink sits OUTSIDE the recorder, so the saved photo never carries the shutter's dimming
    // — which iOS's `drawHierarchy` can catch mid-animation.
    val arrangement = Modifier.blinking(shotsCount).then(recorder)

    Box(modifier, contentAlignment = Alignment.Center) {
        when (style) {
            ComparisonState.Style.SideBySide ->
                SideBySideView(
                    oldYear = oldImage.date.year,
                    currentYear = streetViewYear ?: currentYear,
                    old = old,
                    new = new,
                    modifier = Modifier.fillMaxSize().then(arrangement),
                )

            ComparisonState.Style.CardOnCard ->
                CardOnCardView(
                    oldYear = oldImage.date.year,
                    currentYear = streetViewYear ?: currentYear,
                    oldAspect = oldAspect,
                    old = old,
                    new = new,
                    // iOS sizes the card stack by its cards, which are the old photo's shape
                    // fitted into the frame; the frame's leftover stays empty around it.
                    modifier = Modifier.aspectRatio(oldAspect).then(arrangement),
                )
        }
    }
}

/** iOS `cameraPreview`: the taken frame, the live viewfinder, or a spinner while there is
 *  neither. */
@Composable
private fun NewFrame(
    captureState: ComparisonState.CaptureState?,
    viewfinder: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (captureState) {
        is ComparisonState.CaptureState.Taken ->
            (captureState.capture as? CapturedBitmap)?.let { captured ->
                Image(
                    bitmap = captured.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = ContentScale.Crop,
                )
            }

        ComparisonState.CaptureState.Viewfinder -> viewfinder(modifier)

        null ->
            Box(modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
    }
}

/**
 * Port of iOS `SideBySideView`: new on top, old below, the years on the seam between them.
 *
 * Divergence in the arithmetic only: iOS pins each half to 4:3 and lets the `VStack` find room for
 * the divider; here the two halves split whatever the divider leaves (a hair under 4:3 on the 4:6
 * canvas), so the row can never be squeezed to nothing.
 */
@Composable
private fun SideBySideView(
    oldYear: Int,
    currentYear: Int,
    old: @Composable (Modifier) -> Unit,
    new: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        new(Modifier.fillMaxWidth().weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DividerSpacing),
        ) {
            DividerIcon(Icons.Rounded.KeyboardArrowUp)
            DividerText(currentYear.toString())
            Spacer(Modifier.weight(1f))
            DividerText(WORDMARK)
            Spacer(Modifier.weight(1f))
            DividerText(oldYear.toString())
            DividerIcon(Icons.Rounded.KeyboardArrowDown)
        }
        old(Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun DividerText(text: String) {
    Text(text = text, style = DividerTextStyle, maxLines = 1)
}

@Composable
private fun DividerIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(DividerIconSize))
}

/**
 * Port of iOS `CardOnCardView`: two cards of the old photo's shape, each shrunk toward its own
 * corner so they overlap in the middle, with the years in the two free corners.
 *
 * iOS's `scaleEffect(anchor:)` is a `graphicsLayer` scale about a [TransformOrigin] here; its
 * labels auto-shrink a 1000pt font (`minimumScaleFactor`), which has no Compose counterpart — see
 * [CornerLabel].
 */
@Composable
private fun CardOnCardView(
    oldYear: Int,
    currentYear: Int,
    oldAspect: Float,
    old: @Composable (Modifier) -> Unit,
    new: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(CardStackPadding)) {
        ImageCard(TransformOrigin(0f, 0f), oldAspect, new)
        ImageCard(TransformOrigin(1f, 1f), oldAspect, old)
        CornerLabel(TransformOrigin(1f, 0f), oldAspect, "< $currentYear")
        CornerLabel(TransformOrigin(0f, 1f), oldAspect, "$oldYear >")
        Text(
            text = WORDMARK,
            modifier = Modifier.align(Alignment.TopEnd),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = WORDMARK_ALPHA),
        )
    }
}

/** iOS `.opacity(0.5)` on the wordmark. */
private const val WORDMARK_ALPHA = 0.5f

/** iOS `.padding(5)` around the card stack. */
private val CardStackPadding = 5.dp

@Composable
private fun ImageCard(
    anchor: TransformOrigin,
    oldAspect: Float,
    content: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(oldAspect)
                .scaledFrom(anchor, CARD_SCALE)
                .clip(RoundedCornerShape(CARD_RADIUS))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(CARD_RADIUS),
                ),
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}

/**
 * One of the two year labels, sitting in the corner the cards leave free. iOS sets a 1000pt font
 * and lets `minimumScaleFactor(0.01)` shrink it to whatever fits; [TextAutoSize] is the same idea
 * measured the same way, so the label is as large as its corner allows rather than as large as an
 * estimate of glyph widths said it could be.
 *
 * [BasicText] is the composable that takes it, and it does not read [LocalContentColor] — the
 * colour has to be handed to it, or the label draws in the default black.
 */
@Composable
private fun CornerLabel(
    anchor: TransformOrigin,
    oldAspect: Float,
    text: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(oldAspect)
                .scaledFrom(anchor, 1f - CARD_SCALE)
                .padding(CornerLabelPadding),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = text,
                maxLines = 1,
                autoSize =
                    TextAutoSize.StepBased(
                        minFontSize = MinLabelSize,
                        maxFontSize = MaxLabelSize,
                    ),
                style =
                    TextStyle(
                        color = LocalContentColor.current,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }
    }
}

/** The label is measured at the canvas's full width and only then scaled into its corner, so the
 *  ceiling has to clear what a six-character year fits into at that width. */
private val MinLabelSize = 1.sp
private val MaxLabelSize = 400.sp

/** iOS `.scaleEffect(_:anchor:)`: shrink about a corner of the node's own bounds. */
private fun Modifier.scaledFrom(
    anchor: TransformOrigin,
    scale: Float,
): Modifier =
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        transformOrigin = anchor
    }

/**
 * Port of iOS `BlinkingModifier`: the canvas goes black the instant a shot is triggered and fades
 * back in — the acknowledgement of the tap, fired off the shot counter rather than off the photo
 * actually arriving.
 */
@Composable
private fun Modifier.blinking(trigger: Int): Modifier {
    val shutter = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        shutter.snapTo(1f)
        shutter.animateTo(0f, tween(durationMillis = BLINK_MS))
    }
    return drawWithContent {
        drawContent()
        if (shutter.value > 0f) drawRect(color = Color.Black, alpha = shutter.value)
    }
}
