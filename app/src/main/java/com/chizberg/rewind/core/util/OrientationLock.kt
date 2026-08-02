package com.chizberg.rewind.core.util

/**
 * A screen's demand that the device stop rotating. Port of iOS `OrientationLock`
 * (`Utils/OrientationLock.swift`) — one case there too, and the comparison screen is its one
 * caller: the composite is framed for a portrait canvas.
 *
 * JVM-only, as iOS's own enum keeps `UIInterfaceOrientationMask` in a separate extension; the
 * Android translation into `Activity.requestedOrientation` lives in `app/OrientationLockHost.kt`.
 */
enum class OrientationLock {
    Portrait,
    // if you need to lock into more orientations, add them here
}
