package com.chizberg.rewind.features.comparison

/**
 * How the device is being held. Port of iOS `Orientation` (`OrientationTracker.swift`), names and
 * all: the screen itself is locked to portrait while the comparison is up, and the glyphs of the
 * floating controls are counter-rotated by this instead (iOS `.rotating(on: .phone, with:)`).
 *
 * The tracker proper is platform-side (`app/DeviceOrientationSource.kt`, Android's
 * `OrientationEventListener` where iOS has a `UIDevice` notification) — which is why this file is
 * not called `OrientationTracker` after its iOS original: only the enum is left in it. What crosses
 * into the reducer is that enum, through `Reducer.adding` exactly as iOS's `.adding(signal:)`.
 */
enum class Orientation {
    Portrait,
    LandscapeLeft,
    LandscapeRight,
    UpsideDown,
    ;

    companion object {
        /**
         * The reading of `OrientationEventListener` (degrees the device is turned clockwise from
         * its natural position) as one of the four sides, or null in the dead zones and while the
         * device lies flat (iOS `Orientation(systemValue:)` returns nil for `faceUp`/`faceDown`).
         *
         * 90° means "the device's left edge is up", i.e. it was turned clockwise — the same pose
         * iOS calls `landscapeRight`, and the one its `rotationAngle` answers with -90.
         */
        fun fromDegrees(degrees: Int): Orientation? =
            when {
                degrees < 0 -> null // ORIENTATION_UNKNOWN: flat on a table
                degrees < QUARTER_START -> Portrait
                degrees < HALF_START -> LandscapeRight
                degrees < THREE_QUARTER_START -> UpsideDown
                degrees < FULL_START -> LandscapeLeft
                else -> Portrait
            }
    }
}

// The four 90°-wide sectors, each centred on its side: a device is "portrait" until it has been
// turned a full 45°, and so on around the circle.
private const val QUARTER_START = 45
private const val HALF_START = 135
private const val THREE_QUARTER_START = 225
private const val FULL_START = 315
