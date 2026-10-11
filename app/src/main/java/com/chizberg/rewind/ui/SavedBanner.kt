package com.chizberg.rewind.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chizberg.rewind.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** How long "Saved to Photos" stays up — iOS's `.pause` phase between the two slides. */
private val BannerDuration = 2.seconds

/**
 * iOS `SavedBanner` (`Shared/Views`): a capsule that slides down from the top on every save, waits,
 * and slides back out. Keyed on the save counter, so a second save re-announces itself. Shared by
 * the comparison screen and the fullscreen viewer, as on iOS.
 *
 * Deliberately its own host rather than the app's snackbar: this one belongs to the screen, and
 * M3's bottom snackbar would land under the comparison's shutter.
 */
@Composable
fun SavedBanner(
    savesCount: Int,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    // iOS `phaseAnimator(trigger:)` runs on a *change* of the trigger, never on appear: reopening
    // the fullscreen viewer of a photo saved earlier must not announce that save again.
    val shownCount = remember { savesCount }
    LaunchedEffect(savesCount) {
        if (savesCount == shownCount) return@LaunchedEffect
        visible = true
        delay(BannerDuration)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.DownloadDone, contentDescription = null)
                Text(stringResource(R.string.saved_to_photos))
            }
        }
    }
}
