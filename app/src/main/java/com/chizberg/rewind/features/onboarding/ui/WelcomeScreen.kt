package com.chizberg.rewind.features.onboarding.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Panorama
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.R

/**
 * The onboarding's first page. Port of iOS `WelcomeScreen`: the "Hi! / This is **Rewind**" hero
 * with its brand capsule, then one card per feature — in iOS's order and with its wording.
 *
 * The five blurbs are all shown; iOS drops the comparison and Street-View ones on iPad because both
 * are phone-only there, a distinction this port does not draw (see [OnboardingView]).
 */
@Composable
fun WelcomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        Hero()
        FeatureCard(
            icon = Icons.Rounded.PinDrop,
            title = R.string.onboarding_history_near_you_title,
            description = R.string.onboarding_history_near_you_description,
        )
        FeatureCard(
            icon = Icons.Rounded.Star,
            title = R.string.onboarding_images_saving_title,
            description = R.string.onboarding_images_saving_description,
        )
        FeatureCard(
            icon = Icons.Rounded.CenterFocusWeak,
            title = R.string.onboarding_comparison_title,
            description = R.string.onboarding_comparison_description,
        )
        FeatureCard(
            icon = Icons.Rounded.Panorama,
            title = R.string.onboarding_street_view_title,
            description = R.string.onboarding_street_view_description,
        )
        FeatureCard(
            icon = Icons.Rounded.Translate,
            title = R.string.onboarding_translate_title,
            description = R.string.onboarding_translate_description,
        )
    }
}

@Composable
private fun Hero() {
    Column(
        Modifier.padding(top = HeroTopPadding, bottom = HeroBottomPadding),
        verticalArrangement = Arrangement.spacedBy(HeroSpacing),
    ) {
        Text(
            text = stringResource(R.string.onboarding_hi),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HeroSpacing),
        ) {
            Text(
                text =
                    buildAnnotatedString {
                        append(stringResource(R.string.onboarding_this_is))
                        // The wordmark keeps the brand red on both platforms — see [RewindRed].
                        withStyle(SpanStyle(color = RewindRed)) {
                            append(stringResource(R.string.app_name))
                        }
                    },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            RewindCapsule()
        }
        Text(
            text = stringResource(R.string.onboarding_time_travel_app),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** iOS `RewindCapsule`: the rewind glyph on a brand-red pill, the app's mark beside its name. */
@Composable
private fun RewindCapsule() {
    Surface(shape = CircleShape, color = RewindRed) {
        Icon(
            Icons.Rounded.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier =
                Modifier
                    .padding(horizontal = CapsulePaddingH, vertical = CapsulePaddingV)
                    .size(CapsuleIconSize),
        )
    }
}

/** iOS `makeFeatureDescription`: an accent glyph, then a headline and a line of body text. */
@Composable
private fun FeatureCard(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
) {
    OnboardingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = IconGap).size(FeatureIconSize),
            )
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

private val HeroTopPadding = 40.dp
private val HeroBottomPadding = 10.dp

private val CapsulePaddingH = 10.dp
private val CapsulePaddingV = 6.dp
private val CapsuleIconSize = 24.dp

private val FeatureIconSize = 32.dp
private val IconGap = 16.dp
private val TextGap = 2.dp
