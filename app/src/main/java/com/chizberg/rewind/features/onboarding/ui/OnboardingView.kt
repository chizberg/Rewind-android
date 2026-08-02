package com.chizberg.rewind.features.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.R
import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.features.onboarding.OnboardingAction
import com.chizberg.rewind.features.onboarding.OnboardingModel
import kotlinx.coroutines.launch

/**
 * The first-run wizard. Port of iOS `OnboardingView`: two pages, forward only, no page indicator,
 * each ending in the button that moves on — the second one finishing the whole thing.
 *
 * Divergences from the iOS view:
 * - iOS pushes the second page onto a `NavigationStack`; here the two pages ride a
 *   **forward-only** `HorizontalPager` — the Android idiom, kept linear (a swipe can only go to a
 *   page already reached, so there is no back navigation iOS does not have). No page indicator,
 *   matching iOS: progress is told by the button's label alone.
 * - the button is **hoisted out of the pager** and sits still over the fading bottom edge, so it
 *   does not slide along with a page turn; on iOS each screen carries its own copy in an overlay.
 * - iOS hides the camera-comparison and Street-View blurbs on iPad (both are phone-only features
 *   there). Nothing in this port distinguishes tablets from phones — both features are meant to
 *   work on an Android tablet — so all five blurbs always show.
 *
 * The [scheme] is only read (never written) here, exactly as the map reads it: the annotation
 * legend and the demo year selector on the second page tint themselves with the user's chosen ramp.
 */
@Composable
fun OnboardingView(
    model: OnboardingModel,
    scheme: GradientScheme,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    var maxReachedPage by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val surface = MaterialTheme.colorScheme.surface

    Surface(modifier.fillMaxSize(), color = surface) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Forward only: a page already reached can be flung back to, an unreached one
                // can't.
                userScrollEnabled = pagerState.currentPage < maxReachedPage,
            ) { page ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = ScreenPadding)
                        // Room for the button and its fade, which float over this column.
                        .padding(bottom = BottomBarSpace),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = MaxContentWidth)) {
                        when (page) {
                            0 -> WelcomeScreen()
                            else -> AnnotationsScreen(scheme = scheme)
                        }
                    }
                }
            }

            // iOS `overscrollGradient` at both edges: content dissolves into the background rather
            // than being clipped by it.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(EdgeFadeHeight)
                    .background(Brush.verticalGradient(listOf(surface, Color.Transparent))),
            )

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(EdgeFadeHeight)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, surface))),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(surface)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(ScreenPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = {
                            val next = pagerState.currentPage + 1
                            if (next < PAGE_COUNT) {
                                maxReachedPage = maxOf(maxReachedPage, next)
                                scope.launch { pagerState.animateScrollToPage(next) }
                            } else {
                                model(OnboardingAction.OnboardingFinished)
                            }
                        },
                        contentPadding = ButtonPadding,
                    ) {
                        Text(
                            stringResource(
                                if (pagerState.currentPage == 0) {
                                    R.string.onboarding_get_started
                                } else {
                                    R.string.onboarding_lets_see
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One block of onboarding content. Port of iOS's `onboardingCard()` modifier — every feature blurb,
 * the year-selector demo and the location note each get their own chunky card, which is what makes
 * the two pages read as a list of separate ideas rather than a wall of text.
 */
@Composable
internal fun OnboardingCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(Modifier.padding(CardPadding)) { content() }
    }
}

/**
 * iOS `rewindRed` — the brand accent, a fixed sRGB literal on both platforms. Deliberately not
 * `colorScheme.primary`: the wordmark and its capsule are the app's identity, and the theme's
 * primary can be wallpaper-derived. iOS uses the same literal for exactly these two things.
 */
internal val RewindRed = Color(0xFFB2_3C_36)

internal val ScreenPadding = 16.dp
internal val CardSpacing = 10.dp
internal val HeroSpacing = 8.dp

private const val PAGE_COUNT = 2

private val CardCorner = 25.dp
private val CardPadding = 16.dp
private val EdgeFadeHeight = 48.dp

/** Enough room under the last card that the floating button never covers it. */
private val BottomBarSpace = 112.dp

private val MaxContentWidth = 560.dp

private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
