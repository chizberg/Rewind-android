package com.chizberg.rewind.app

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * The Android counterpart of iOS's `UIApplication.didReceiveMemoryWarningNotification`: runs
 * [onPressure] on `onLowMemory` and on `TRIM_MEMORY_BACKGROUND` or worse. Not the same signal:
 * since API 34 the only trims still delivered are `UI_HIDDEN` and `BACKGROUND`, and `BACKGROUND`
 * means the process went into the cached list — which happens on every trip to the background,
 * pressure or not. So whatever [onPressure] frees is dropped each time the user leaves the app,
 * and paid for again on return.
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
