package com.chizberg.rewind.app

import android.content.Context
import android.view.OrientationEventListener
import com.chizberg.rewind.features.comparison.Orientation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * How the device is held, as a flow. Port of iOS `OrientationTracker`, which listens for
 * `UIDevice.orientationDidChangeNotification`; Android's counterpart is the accelerometer-backed
 * [OrientationEventListener], which reports degrees rather than sides (see
 * [Orientation.fromDegrees]).
 *
 * The listener fires on every degree of movement, so the flow is de-duplicated down to the four
 * sides — the reducer only ever hears an actual change, as iOS's `newValues` signal does.
 * `callbackFlow` unregisters the sensor when the collection (the comparison model's scope) ends.
 */
fun deviceOrientation(context: Context): Flow<Orientation> =
    callbackFlow {
        val listener =
            object : OrientationEventListener(context.applicationContext) {
                override fun onOrientationChanged(orientation: Int) {
                    Orientation.fromDegrees(orientation)?.let { trySend(it) }
                }
            }
        if (listener.canDetectOrientation()) listener.enable()
        awaitClose { listener.disable() }
    }.distinctUntilChanged()
