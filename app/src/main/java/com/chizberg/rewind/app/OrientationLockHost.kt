package com.chizberg.rewind.app

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chizberg.rewind.core.util.OrientationLock
import kotlinx.coroutines.flow.StateFlow

/**
 * Applies whatever screen currently demands a fixed orientation. Port of iOS `AppDelegate`, which
 * answers `application(_:supportedInterfaceOrientationsFor:)` out of the same
 * `AppGraph.orientationLock` property this collects — only the details screen writes it, and only
 * while the comparison is up.
 *
 * Divergence: iOS locks on the phone only (`withUIIdiom(phone:pad: nil)`, which is also why its
 * compare buttons are phone-only). Here the buttons are offered on every form factor — an Android
 * tablet has a camera and Street View works anywhere — so the lock applies everywhere too.
 *
 * A lock is a configuration change, so the activity is recreated; nothing is lost by that (the
 * graph lives in the ViewModel, see `RootView`), and the new composition re-applies the lock.
 */
@Composable
fun OrientationLockHost(lock: StateFlow<OrientationLock?>) {
    val activity = LocalActivity.current
    val current by lock.collectAsStateWithLifecycle()

    DisposableEffect(activity, current) {
        activity?.requestedOrientation =
            when (current) {
                OrientationLock.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
