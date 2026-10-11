package com.chizberg.rewind.app

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * The Android counterpart of iOS's `UIApplication.didReceiveMemoryWarningNotification`: runs
 * [onPressure] when the system says memory is short — on `onLowMemory`, and on a trim that comes
 * while the app sits in the background (the only trims still delivered since API 34 are
 * `UI_HIDDEN` and `BACKGROUND`; `UI_HIDDEN` alone is just "the user left", not pressure).
 */
class MemoryPressureObserver(
    private val onPressure: () -> Unit,
) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) onPressure()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = onPressure()
}
