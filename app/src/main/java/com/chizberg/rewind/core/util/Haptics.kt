package com.chizberg.rewind.core.util

/**
 * The vocabulary of taps the app can play, one member per iOS feedback generator family so a
 * reducer reads the same on both platforms:
 *  - [success] / [error] — `UINotificationFeedbackGenerator().notificationOccurred(.success/.error)`
 *  - [selection] — `UISelectionFeedbackGenerator().selectionChanged()`
 *  - [impactLight] — `UIImpactFeedbackGenerator(style: .light).impactOccurred()`
 *
 * JVM-only, exactly like [OrientationLock]: reducers name the *intent*, the Android translation
 * into `HapticFeedbackConstants` lives in `app/AndroidHaptics.kt`. iOS constructs its generators
 * inline in `reduce`; the same calls sit in the same branches here, wrapped in an `effect { }` as
 * every other side effect in this repo is.
 */
interface Haptics {
    fun success()

    fun error()

    fun selection()

    fun impactLight()

    companion object {
        /** Plays nothing — the default for reducers built by a test, which has no device to buzz. */
        val None: Haptics =
            object : Haptics {
                override fun success() = Unit

                override fun error() = Unit

                override fun selection() = Unit

                override fun impactLight() = Unit
            }
    }
}
