package com.chizberg.rewind.app

import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.chizberg.rewind.BuildConfig
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

private const val TAG = "ReviewPrompter"

/**
 * The Activity-bound edge of [ReviewPrompter] — the third "this cannot be headless" case after
 * M13.5's location permission and M14's camera. iOS reaches its store straight from the model
 * (`AppStore.requestReview(in: UIApplication.shared.activeWindowScene)`); Play's
 * `launchReviewFlow` wants an `Activity`, which only exists in composition. So the model rings
 * [requests] and this answers it.
 *
 * Unlike [LocationPermissionHost] nothing comes back: iOS gets no verdict from StoreKit either, and
 * Play deliberately hides whether a card was shown (it throttles per user per app, and a dropped
 * flow is indistinguishable from a shown one). Failures are therefore swallowed the same way iOS's
 * `guard let scene … else { return }` swallows a missing scene — there is nothing to tell the user.
 *
 * Debug builds only log, mirroring iOS's `#if DEBUG print(...)`: an APK that did not come from Play
 * cannot show the card anyway, and the log is how the counter logic is observed while developing.
 */
@Composable
@Suppress("TooGenericExceptionCaught")
fun ReviewPromptHost(requests: Flow<Unit>) {
    val activity = LocalActivity.current

    LaunchedEffect(requests, activity) {
        if (activity == null) return@LaunchedEffect
        val manager = ReviewManagerFactory.create(activity)
        requests.collect {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "In-app review requested")
                return@collect
            }
            try {
                manager.launchReview(activity, manager.requestReview())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "In-app review unavailable", e)
            }
        }
    }
}
