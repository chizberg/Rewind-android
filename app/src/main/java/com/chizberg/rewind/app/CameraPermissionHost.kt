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
import kotlinx.coroutines.flow.Flow

/**
 * The runtime-permission edge of the comparison screen — the same split M13.5 made for location,
 * one screen down. iOS asks straight from its reducer (`AVCaptureDevice.requestAccess(for: .video)`
 * inside `viewWillAppear`) and gets the verdict back as an await; Android's dialog needs an
 * Activity result launcher, which only exists in composition.
 *
 * So the reducer's `viewWillAppear` rings [requests] (through `CameraSession.requestAccess`), this
 * answers it, and the verdict goes back as one boolean — `granted` becomes iOS's
 * `.internal(.videoAccessGranted)`, a refusal its `.alert(.presentAccessError)`.
 *
 * Unlike the location host this one does not re-report on resume: iOS's access alert has no way
 * into the system settings either (it only says where to look), so nothing can change under it
 * while the screen is up.
 */
@Composable
fun CameraPermissionHost(
    requests: Flow<Unit>,
    onAccessChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val currentOnAccessChanged by rememberUpdatedState(onAccessChanged)
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> currentOnAccessChanged(granted) }

    LaunchedEffect(requests) {
        requests.collect {
            if (context.hasCameraAccess()) {
                currentOnAccessChanged(true)
            } else {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

private fun Context.hasCameraAccess(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
