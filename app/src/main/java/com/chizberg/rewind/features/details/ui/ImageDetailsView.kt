package com.chizberg.rewind.features.details.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.details.ImageDetailsAction
import com.chizberg.rewind.features.details.ImageDetailsModel
import com.chizberg.rewind.features.details.ImageDetailsState
import com.chizberg.rewind.features.details.MapApp
import com.chizberg.rewind.features.map.ui.RewindAsyncImage
import com.chizberg.rewind.network.ImageQuality
import com.chizberg.rewind.ui.DirectionBadge
import com.chizberg.rewind.ui.ImageDateBadge
import com.chizberg.rewind.ui.OverlayScreen

private const val PASTVU_BASE = "https://pastvu.com"

/**
 * What the text block and the action tiles are drawn on: the lowest container tone, which is plain
 * white in a light scheme and near-black in a dark one — the M3 counterpart of iOS
 * `.systemBackground`, which is what those two carry there. `surface` would not do: it is a tinted
 * near-white a hair away from the page tone, and the tiles dissolved into it.
 */
private val BlockColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest

// The active favorite button's fill (iOS `.yellow.mix(with: .black, by: 0.1)`).
private val FavoriteYellow = Color(0xFFE0B32E)

/**
 * Where the photo stops being a full-width header and moves beside its metadata. Port of iOS
 * `horizontalSizeClass == .regular`; on Android the equivalent is M3's medium width breakpoint —
 * tablets, unfolded foldables, and a phone held landscape only if it is wide enough.
 */
private val SplitWidthThreshold = 600.dp

/** iOS `scroll.frame(width: 325)`: the metadata column next to the photo in the split layout. */
private val SidebarWidth = 325.dp

/** The photo's reserved box before anything has loaded — 4:3, the shape of most PastVu scans. */
private const val PLACEHOLDER_ASPECT = 4f / 3f

/** iOS `.blur(radius: 7)` over the text while a linked photo loads. */
private val DescriptionBlur = 7.dp

/**
 * The action tiles' corner, and the corner the favorite tile morphs to once it is on — the M3
 * expressive "shape change carries the state change" idiom (`ToggleButton`'s round↔square morph;
 * the component itself only lands in material3 1.5, the motion is the same). 28dp exceeds half the
 * tile's height, so the checked shape reads as a pill.
 */
private val ButtonCorner = 15.dp
private val ButtonCheckedCorner = 28.dp

/**
 * The spinners inside the text block. iOS uses a plain `ProgressView()` there (only the one over the
 * photo is scaled up, `.scaleEffect(1.5)`); M3's default indicator is twice that size, so it is
 * brought back down to the iOS footprint, stroke included.
 */
private val TextSpinnerSize = 20.dp
private val TextSpinnerStroke = 2.dp

/**
 * The image-details screen. Port of iOS `ImageDetailsView`. Renders one [ImageDetailsModel] and its
 * overlays: a route-picker dialog, a full-screen zoomable image, an error alert, and — recursively —
 * a nested details screen opened from a pastvu link in the description. Back closes the topmost layer
 * first (a nested screen before its parent). Details load on first appearance (`willBePresented`).
 *
 * M9 scope: picture, HTML text (with recursion-routed links), and the favorite / show-on-map /
 * view-on-web / find-route actions. Share, save, comparison (M14) and translation (M15) land later.
 */
@Composable
fun ImageDetailsView(
    model: ImageDetailsModel,
    scheme: GradientScheme,
    maxRange: IntRange,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(model) { model(ImageDetailsAction.WillBePresented) }

    // Backgrounds bleed under the system bars and the cutout (iOS `.ignoresSafeArea()` on the
    // backing rectangles); the safe area is padding *inside* them, never around them — padding the
    // screen itself would shrink the scroll viewport and leave dead strips top and bottom.
    //
    // The page is the *recessed* tone (iOS `.secondarySystemBackground`) so the text block and the
    // action tiles can sit on the raised one (see [BlockColor]) — the layering iOS gets for free
    // from its two system backgrounds.
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints {
                if (maxWidth >= SplitWidthThreshold) {
                    SplitContent(state, scheme, maxRange, model)
                } else {
                    ScrollContent(state, scheme, maxRange, model)
                }
            }

            BackButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .padding(8.dp),
            )
        }
    }

    if (state.mapOptionsPresented) {
        MapAppDialog(
            onSelect = { model(ImageDetailsAction.MapAppSelected(it)) },
            onDismiss = { model(ImageDetailsAction.SetMapOptionsVisibility(false)) },
        )
    }

    if (state.fullscreenPresented) {
        FullscreenImage(
            image = state.image,
            onDismiss = { model(ImageDetailsAction.FullscreenPreview.Dismiss) },
        )
    }

    state.alert?.let { params ->
        DetailsAlert(
            title = params.title,
            message = params.message,
            onDismiss = { model(ImageDetailsAction.Alert.Dismiss) },
        )
    }

    // Recursion: a pastvu-link tap presents the linked photo as a full-screen screen on top. Its own
    // OverlayScreen composes after ours, so the back dispatcher (LIFO) gives it the gesture first.
    OverlayScreen(
        target = state.anotherImageModel,
        onBack = { model(ImageDetailsAction.AnotherImage.Dismiss) },
    ) { nested ->
        ImageDetailsView(
            model = nested,
            scheme = scheme,
            maxRange = maxRange,
            onDismiss = { model(ImageDetailsAction.AnotherImage.Dismiss) },
        )
    }
}

/**
 * The phone layout: photo, text and buttons in one scroll. The single pane owns every screen edge,
 * so it takes the whole safe area — as padding of the scrolling content (the modifier sits after
 * `verticalScroll`), so the surface keeps bleeding under the bars and the photo scrolls under the
 * status bar instead of the viewport being permanently shortened.
 */
@Composable
private fun ScrollContent(
    state: ImageDetailsState,
    scheme: GradientScheme,
    maxRange: IntRange,
    model: ImageDetailsModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        DetailsPicture(
            image = state.image,
            onTap = { model(ImageDetailsAction.FullscreenPreview.Present) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextDetails(
            state = state,
            scheme = scheme,
            maxRange = maxRange,
            onLink = { model(ImageDetailsAction.DescriptionLink(it)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(BlockColor)
                    .padding(16.dp),
        )
        ActionButtons(
            state = state,
            dispatch = model::invoke,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * The wide layout: the photo fills a black pane (full-bleed, as iOS lets its black rectangle ignore
 * the safe area) with the metadata scrolling in a fixed column beside it. Port of iOS `isSplitView`.
 *
 * Each pane is inset only on the edges it actually touches — the photo the leading one, the column
 * the trailing one, both the horizontal bars. Handing a pane the *whole* safe area (what a bare
 * `windowInsetsPadding` does) would pad the column away from a cutout sitting on the far side of the
 * screen — a gap with nothing behind it.
 */
@Composable
private fun SplitContent(
    state: ImageDetailsState,
    scheme: GradientScheme,
    maxRange: IntRange,
    model: ImageDetailsModel,
) {
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // iOS `ZStack { Rectangle().fill(.black).ignoresSafeArea(); picture }`: only the black
            // backing ignores the safe area — the photo itself keeps clear of the bars and the
            // cutout, and what shows through there is that black, not the screen's surface.
            DetailsPicture(
                image = state.image,
                onTap = { model(ImageDetailsAction.FullscreenPreview.Present) },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Start + WindowInsetsSides.Vertical,
                            ),
                        ),
                contentScale = ContentScale.Fit,
                reserveSpace = false,
            )
        }
        Column(
            Modifier
                .width(SidebarWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.End + WindowInsetsSides.Vertical,
                    ),
                ),
        ) {
            TextDetails(
                state = state,
                scheme = scheme,
                maxRange = maxRange,
                onLink = { model(ImageDetailsAction.DescriptionLink(it)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(BlockColor)
                        .padding(16.dp),
            )
            ActionButtons(
                state = state,
                dispatch = model::invoke,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * The photo. Port of iOS `picture`: whatever is drawn — the cached lower-quality rendition or the
 * full one — sets the aspect ratio (`.aspectRatio(contentMode: .fit)`), and a spinner stays up until
 * the full one arrives (iOS keeps its `ProgressView` while `uiImage == nil`).
 *
 * The one addition to iOS: with *nothing* drawn yet there is no aspect to take, and iOS's `Color
 * .clear` would collapse the box; a 4:3 placeholder (the shape of most PastVu scans) holds the space
 * instead. It is dropped the moment any rendition appears, so the picture never resizes on arrival
 * of the full-quality one — a cached medium already has the final aspect. [reserveSpace] is off in
 * the split layout, where the pane's own size decides the box.
 */
@Composable
private fun DetailsPicture(
    image: ModelImage,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    reserveSpace: Boolean = true,
) {
    var state by
        remember(image.cid) {
            mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
        }
    val nothingDrawn = state.painter == null
    val reserved =
        if (reserveSpace && nothingDrawn) Modifier.aspectRatio(PLACEHOLDER_ASPECT) else Modifier
    Box(
        modifier
            .then(reserved)
            .pointerInput(image.cid) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center,
    ) {
        RewindAsyncImage(
            path = image.imagePath,
            contentDescription = image.title,
            modifier = if (reserveSpace) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            quality = ImageQuality.High,
            contentScale = contentScale,
            placeholderQuality = ImageQuality.Medium,
            onState = { state = it },
        )
        if (state !is AsyncImagePainter.State.Success) CircularProgressIndicator()
    }
}

/**
 * Title, description and the labelled fields.
 *
 * Two loads show up here. The screen's own (`details == null`) replaces the block with a spinner,
 * as on iOS. A *link* load — a tap on a pastvu photo, which ends in a nested screen — blurs the
 * description behind a spinner, as on iOS; over a slow connection that stretch is long, and without
 * it the tap reads as if nothing happened.
 *
 * Taps are dropped everywhere for that stretch, not only in the description: iOS reroutes link taps
 * into the reducer for the description alone, ours linkifies author, source and address through the
 * same route, and a second load started from one of them would race the first.
 */
@Composable
private fun TextDetails(
    state: ImageDetailsState,
    scheme: GradientScheme,
    maxRange: IntRange,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading = state.loadingAnotherImage
    val blurRadius by animateDpAsState(if (loading) DescriptionBlur else 0.dp, label = "blur")
    // The guard reads a live flag: the annotated strings — and the listeners baked into them — are
    // cached across the load, so a `loading` captured while parsing would go stale.
    val loadingNow = rememberUpdatedState(loading)
    val currentOnLink = rememberUpdatedState(onLink)
    val guardedLink =
        remember { { url: String -> if (!loadingNow.value) currentOnLink.value(url) } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TitleRow(state.image, scheme, maxRange)

        val details = state.details
        if (details == null) {
            // iOS keeps a ProgressView in place of the whole block until the payload lands.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextSpinner()
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    details.description?.let { desc ->
                        Box(contentAlignment = Alignment.Center) {
                            HtmlText(desc, guardedLink, Modifier.blur(blurRadius))
                            if (loading) TextSpinner()
                        }
                    }
                    LabeledText("uploaded by", AnnotatedString(details.username))
                    details.author?.let { LabeledText("author", htmlAnnotated(it, guardedLink)) }
                    details.source?.let { LabeledText("source", htmlAnnotated(it, guardedLink)) }
                    details.address?.let { LabeledText("address", htmlAnnotated(it, guardedLink)) }
                }
                // A photo without a description has nothing to blur, but a link in one of its other
                // fields still starts a load that has to show somewhere.
                if (loading && details.description == null) TextSpinner()
            }
        }
    }
}

@Composable
private fun TitleRow(
    image: ModelImage,
    scheme: GradientScheme,
    maxRange: IntRange,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = image.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ImageDateBadge(image.date, scheme, maxRange)
            image.dir?.let { DirectionBadge(image.date, it, scheme, maxRange) }
        }
    }
}

@Composable
private fun LabeledText(
    label: String,
    value: AnnotatedString,
) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TextSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(TextSpinnerSize),
        strokeWidth = TextSpinnerStroke,
    )
}

@Composable
private fun HtmlText(
    html: String,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = htmlAnnotated(html, onLink),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Parses PastVu's HTML into a linkified [AnnotatedString]. Relative hrefs (iOS gets `applewebdata://`
 * ones and rewrites them to `pastvu.com`) are resolved against [PASTVU_BASE]; a tapped link is routed
 * to [onLink] — which the reducer turns into a recursion (pastvu photo) or a browser hand-off.
 */
@Composable
private fun htmlAnnotated(
    html: String,
    onLink: (String) -> Unit,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(html, linkColor) {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles =
                TextLinkStyles(
                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ),
            linkInteractionListener =
                LinkInteractionListener { link ->
                    (link as? LinkAnnotation.Url)?.let { onLink(resolvePastvuUrl(it.url)) }
                },
        )
    }
}

private fun resolvePastvuUrl(url: String): String =
    when {
        url.startsWith("http") -> url
        url.startsWith("/") -> PASTVU_BASE + url
        else -> "$PASTVU_BASE/$url"
    }

@Composable
private fun ActionButtons(
    state: ImageDetailsState,
    dispatch: (ImageDetailsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    TwoColumnLayout(modifier) {
        state.actionButtons.forEach { button ->
            ActionButton(
                button = button,
                isFavorite = state.isFavorite,
                onClick = { dispatch(ImageDetailsAction.OnButton(button)) },
            )
        }
    }
}

/**
 * One tile of the action grid. The favorite tile is the only stateful one, and it announces its
 * state the M3 expressive way — the shape morphs, springing from a rounded square to a pill as the
 * fill turns yellow — instead of relying on the colour swap alone.
 */
@Composable
private fun ActionButton(
    button: ImageDetailsAction.Button,
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = button == ImageDetailsAction.Button.Favorite && isFavorite
    val corner by
        animateDpAsState(
            targetValue = if (active) ButtonCheckedCorner else ButtonCorner,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "buttonCorner",
        )
    val container by
        animateColorAsState(
            if (active) FavoriteYellow else BlockColor,
            label = "buttonContainer",
        )
    val content by
        animateColorAsState(
            if (active) Color.Black else MaterialTheme.colorScheme.onSurface,
            label = "buttonContent",
        )
    Surface(
        onClick = onClick,
        modifier = modifier,
        // The spring overshoots past the target on the way in; a negative corner would throw.
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(button.icon(isFavorite), contentDescription = null)
            Text(button.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        }
    }
}

private val ImageDetailsAction.Button.label: String
    get() =
        when (this) {
            ImageDetailsAction.Button.Favorite -> "Favorite"
            ImageDetailsAction.Button.ShowOnMap -> "Show on map"
            ImageDetailsAction.Button.ViewOnWeb -> "View on Web"
            ImageDetailsAction.Button.Route -> "Find route"
        }

private fun ImageDetailsAction.Button.icon(isFavorite: Boolean): ImageVector =
    when (this) {
        ImageDetailsAction.Button.Favorite ->
            if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder

        ImageDetailsAction.Button.ShowOnMap -> Icons.Rounded.Place
        ImageDetailsAction.Button.ViewOnWeb -> Icons.Rounded.Public
        ImageDetailsAction.Button.Route -> Icons.Rounded.Directions
    }

/**
 * The floating back chip over the photo (iOS `BackButton`, a glass circle). Both colours are named
 * explicitly: a translucent container is not a palette entry, so `Surface` couldn't derive a content
 * colour for it and the arrow kept the ambient one — black on a dark chip in the dark theme.
 */
@Composable
private fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun MapAppDialog(
    onSelect: (MapApp) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select map app to find route") },
        text = {
            Column {
                MapApp.entries.forEach { app ->
                    TextButton(onClick = {
                        onSelect(app)
                        onDismiss()
                    }) { Text(app.appName) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val MapApp.appName: String
    get() =
        when (this) {
            MapApp.Google -> "Google Maps"
            MapApp.Yandex -> "Yandex Maps"
        }

@Composable
private fun FullscreenImage(
    image: ModelImage,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        val transformState =
            rememberTransformableState { zoomChange, panChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, 5f)
                offsetX += panChange.x
                offsetY += panChange.y
            }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            RewindAsyncImage(
                path = image.imagePath,
                contentDescription = image.title,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                quality = ImageQuality.High,
                contentScale = ContentScale.Fit,
                placeholderQuality = ImageQuality.Medium,
            )
        }
    }
}

@Composable
private fun DetailsAlert(
    title: String?,
    message: String?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton =
            message?.let {
                {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(it))
                        onDismiss()
                    }) { Text("Copy to clipboard") }
                }
            },
    )
}
