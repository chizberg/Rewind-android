package com.chizberg.rewind.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.Flow

/**
 * The runtime-permission edge of the location feature — Android's stand-in for the part of
 * `CLLocationManager` that has no headless equivalent here. iOS asks straight from the reducer
 * (`manager.requestWhenInUseAuthorization()`) and is told the verdict by its delegate; on Android
 * the dialog needs an Activity result launcher, which only exists in composition.
 *
 * So: the reducer's `requestAccess` rings [requests], this answers it, and every verdict goes back
 * in through [onAccessChanged] as one `DidChangeAuthorizationStatus` — nothing is kept as loose
 * Compose state. `ON_RESUME` re-reports too, which covers both the first frame (iOS gets
 * `locationManagerDidChangeAuthorization` as soon as the delegate is set) and a return from the
 * system settings page after the "Go to Settings" alert.
 *
 * Coarse-only counts as granted: precision is not distinguished, exactly as iOS does not
 * distinguish reduced accuracy.
 */
@Composable
fun LocationPermissionHost(
    requests: Flow<Unit>,
    onAccessChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val currentOnAccessChanged by rememberUpdatedState(onAccessChanged)
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { currentOnAccessChanged(context.hasLocationAccess()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        currentOnAccessChanged(context.hasLocationAccess())
    }

    LaunchedEffect(requests) {
        requests.collect {
            if (context.hasLocationAccess()) {
                currentOnAccessChanged(true)
            } else {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }
}

/** Either location permission counts (iOS `CLAuthorizationStatus.isAuthorized`, precision aside). */
private fun Context.hasLocationAccess(): Boolean =
    isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

private fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
