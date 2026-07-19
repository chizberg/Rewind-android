package com.chizberg.rewind.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/**
 * Retains the [AppGraph] across activity recreation so a rotation — or any recreate while the process
 * is alive — keeps the loaded map, filters, and open screen instead of rebuilding from scratch. On
 * iOS this state lives in the app object for the process lifetime; a ViewModel is the Android
 * equivalent. The graph runs on [viewModelScope] (main dispatcher + SupervisorJob — the same shape
 * RootView used to build by hand), cancelled only when the VM is cleared, i.e. when the activity is
 * genuinely finishing, not on a configuration change.
 *
 * Holds the application context, never the Activity, so nothing leaks across the recreations it
 * outlives. Process death still drops this (the OS reclaims the whole VM); the map camera is restored
 * separately from saved instance state — maps-compose `rememberCameraPositionState` is backed by
 * `rememberSaveable` — so a cold restore reopens at the same place and reloads content there.
 */
class RewindViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val graph: AppGraph = AppGraph(application, viewModelScope)
}
