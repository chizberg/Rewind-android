package com.chizberg.rewind.features.details.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ModelImage
import com.chizberg.rewind.features.details.ImageDetailsAction
import com.chizberg.rewind.features.details.ImageDetailsModel
import com.chizberg.rewind.features.details.ImageDetailsState
import com.chizberg.rewind.features.details.MapApp
import com.chizberg.rewind.features.map.ui.RewindAsyncImage
import com.chizberg.rewind.network.ImageQuality
import com.chizberg.rewind.ui.OverlayScreen
import com.chizberg.rewind.ui.toComposeColor

private const val PASTVU_BASE = "https://pastvu.com"

// The active favorite button's fill (iOS `.yellow.mix(with: .black, by: 0.1)`).
private val FavoriteYellow = Color(0xFFE0B32E)

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

    Box(modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                DetailsPicture(state.image) {
                    model(ImageDetailsAction.FullscreenPreview.Present)
                }
                TextDetails(
                    state = state,
                    scheme = scheme,
                    maxRange = maxRange,
                    onLink = { model(ImageDetailsAction.DescriptionLink(it)) },
                    modifier = Modifier.padding(16.dp),
                )
                ActionButtons(
                    state = state,
                    dispatch = model::invoke,
                    modifier = Modifier.padding(16.dp),
                )
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

@Composable
private fun DetailsPicture(
    image: ModelImage,
    onTap: () -> Unit,
) {
    RewindAsyncImage(
        path = image.imagePath,
        contentDescription = image.title,
        quality = ImageQuality.High,
        contentScale = ContentScale.FillWidth,
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(image.cid) { detectTapGestures { onTap() } },
    )
}

@Composable
private fun TextDetails(
    state: ImageDetailsState,
    scheme: GradientScheme,
    maxRange: IntRange,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TitleRow(state.image, scheme, maxRange)

        val details = state.details
        if (details == null) {
            Text("…", style = MaterialTheme.typography.bodyMedium)
        } else {
            details.description?.let { HtmlText(it, onLink) }
            LabeledText("uploaded by", AnnotatedString(details.username))
            details.author?.let { LabeledText("author", htmlAnnotated(it, onLink)) }
            details.source?.let { LabeledText("source", htmlAnnotated(it, onLink)) }
            details.address?.let { LabeledText("address", htmlAnnotated(it, onLink)) }
        }
    }
}

@Composable
private fun TitleRow(
    image: ModelImage,
    scheme: GradientScheme,
    maxRange: IntRange,
) {
    val tint = scheme.color(image.date.year, maxRange)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = image.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = tint.toComposeColor(),
            ) {
                Text(
                    text = image.date.description,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.foreground(tint).toComposeColor(),
                )
            }
            image.dir?.angleDegrees?.let { angle ->
                Icon(
                    imageVector = Icons.Rounded.Navigation,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .padding(start = 8.dp)
                            .rotate(angle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun HtmlText(
    html: String,
    onLink: (String) -> Unit,
) {
    Text(text = htmlAnnotated(html, onLink), style = MaterialTheme.typography.bodyMedium)
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.actionButtons.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { button ->
                    ActionButton(
                        button = button,
                        isFavorite = state.isFavorite,
                        onClick = { dispatch(ImageDetailsAction.OnButton(button)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionButton(
    button: ImageDetailsAction.Button,
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = button == ImageDetailsAction.Button.Favorite && isFavorite
    val container =
        if (active) FavoriteYellow else MaterialTheme.colorScheme.surfaceContainerHighest
    val content =
        if (active) Color.Black else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
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

@Composable
private fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
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
            MapApp.Apple -> "Apple Maps"
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
                quality = ImageQuality.High,
                contentScale = ContentScale.Fit,
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
