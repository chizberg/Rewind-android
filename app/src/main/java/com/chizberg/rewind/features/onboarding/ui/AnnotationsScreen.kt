package com.chizberg.rewind.features.onboarding.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.R
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageRequestFilters
import com.chizberg.rewind.features.map.ui.AnnotationIconFactory
import com.chizberg.rewind.features.map.ui.YearSelector

/**
 * The onboarding's second page. Port of iOS `AnnotationsScreen`: what the three kinds of map marker
 * mean, how a date turns into a colour (with a live year selector to play with), the PastVu credit
 * and the note about location — in iOS's order.
 *
 * The legend markers are the **real** ones: [AnnotationIconFactory] is the same rasterizer the map
 * uses, called here off-map with a fixed demo year, so a pin looks in the tutorial exactly like it
 * looks on the map (iOS instantiates its actual `MKAnnotationView` subclasses for the same reason).
 * The year selector is likewise the map's own control, driven by state local to this page —
 * dragging it here must never touch the map's filters.
 */
@Composable
fun AnnotationsScreen(
    scheme: GradientScheme,
    modifier: Modifier = Modifier,
) {
    val maxRange = ImageRequestFilters.ImageKind.Photo.maxRange
    var demoRange by remember { mutableStateOf(maxRange) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(SectionSpacing)) {
        Column(
            Modifier.padding(top = HeroTopPadding),
            verticalArrangement = Arrangement.spacedBy(HeroSpacing),
        ) {
            Text(
                text = stringResource(R.string.onboarding_history_on_a_map),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.onboarding_what_do_images_look_like),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Legend(scheme = scheme, maxRange = maxRange)

        OnboardingCard {
            Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
                Text(
                    text = stringResource(R.string.onboarding_date),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                YearSelector(
                    yearRange = demoRange,
                    maxRange = maxRange,
                    scheme = scheme,
                    onYearRangeChange = { demoRange = it },
                )
            }
        }

        Text(
            text = stringResource(R.string.onboarding_pastvu),
            modifier = Modifier.padding(horizontal = FootnoteInset),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OnboardingCard {
            Text(
                text = stringResource(R.string.onboarding_location),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * The three marker kinds with their explanations. The tints come from [scheme] at a fixed demo year
 * (iOS's `Model.Image.demo`, 1861) — deliberately not tied to the live selector above, so the
 * legend stays still while the demo range is dragged.
 *
 * The server cluster carries a photo, as it does on the map: iOS's `Model.Cluster.demo` is built
 * around a `preview` whose image is the bundled `demo.jpg`, and the same file ships here (an
 * annotation shown with the empty placeholder would be explaining the wrong thing). The source is
 * decoded inside the raster's `remember`, so it is garbage the moment the icon is drawn.
 */
@Composable
private fun Legend(
    scheme: GradientScheme,
    maxRange: IntRange,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val icons = remember(context, density) { AnnotationIconFactory(context, density) }
    val tint = scheme.color(DEMO_YEAR, maxRange)
    val foreground = scheme.foreground(tint)

    Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
        LegendCard(
            icon = remember(icons, tint, foreground) { icons.pinBitmap(tint, foreground) },
            title = R.string.onboarding_single_image_title,
            description = R.string.onboarding_single_image_description,
        )
        LegendCard(
            icon =
                remember(icons, tint, foreground) {
                    icons.bubbleBitmap(tint, foreground, DEMO_GROUP_COUNT)
                },
            title = R.string.onboarding_group_of_images_title,
            description = R.string.onboarding_group_of_images_description,
        )
        LegendCard(
            icon =
                remember(icons, tint, foreground) {
                    val preview =
                        BitmapFactory.decodeResource(
                            context.resources,
                            R.drawable.onboarding_demo,
                            BitmapFactory.Options().apply { inSampleSize = DEMO_SAMPLE_SIZE },
                        )
                    icons.serverClusterBitmap(preview, tint, foreground, DEMO_CLUSTER_COUNT)
                },
            title = R.string.onboarding_cluster_of_images_title,
            description = R.string.onboarding_cluster_of_images_description,
        )
    }
}

@Composable
private fun LegendCard(
    icon: Bitmap,
    @StringRes title: Int,
    @StringRes description: Int,
) {
    OnboardingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(MarkerSlot).padding(end = IconGap),
                contentAlignment = Alignment.Center,
            ) {
                // Rendered 1:1 in pixels, as on the map — the bitmap was rasterized at this
                // density.
                Image(bitmap = icon.asImageBitmap(), contentDescription = null)
            }
            Column(verticalArrangement = Arrangement.spacedBy(TextGap)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** iOS `Model.Image.demo`: year 1861, and the counts its cluster demos carry. */
private const val DEMO_YEAR = 1861
private const val DEMO_GROUP_COUNT = 3
private const val DEMO_CLUSTER_COUNT = 150

/**
 * The demo photo ends up centre-cropped into a 60dp disc, so the 961px source is halved on the way
 * in rather than decoded whole: even at 4x density that leaves the disc oversampled.
 */
private const val DEMO_SAMPLE_SIZE = 2

private val HeroTopPadding = 40.dp
private val SectionSpacing = 20.dp

/** iOS's `frame(squareSize: 60)` slot the annotation view is centred in. */
private val MarkerSlot = 76.dp
private val IconGap = 16.dp
private val TextGap = 2.dp
private val FootnoteInset = 7.dp
