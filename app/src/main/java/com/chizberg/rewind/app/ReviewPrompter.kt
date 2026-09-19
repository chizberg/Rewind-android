package com.chizberg.rewind.app

import com.chizberg.rewind.core.redux.Property

/**
 * One prompt per this many [ReviewPrompter.request] calls (iOS `requestCount.isMultiple(of: 10)`).
 * The check runs BEFORE the counter goes up, so the first eligible call prompts (`0 % 10 == 0`).
 */
private const val REQUEST_PERIOD = 10

/**
 * Decides when to ask for a store review. Port of iOS `AppStoreReview`
 * (`App/AppStoreReview.swift`); named after `plan.md`'s M16 entry rather than after the iOS class,
 * since "App Store" is the wrong shop here — everything else about it is 1:1.
 *
 * Two persisted counters, both defaulting to 0 and both under iOS's own keys (see [AppGraph]):
 *  - [launchCount] goes up once per app launch ([appLaunched]);
 *  - [requestCount] counts the *opportunities* to prompt, not the prompts.
 *
 * The order inside [request] is deliberate and is the part worth reading twice: the launch guard
 * comes first, so a first-launch opportunity is not counted at all; the period check then reads the
 * counter BEFORE incrementing it, so the very first opportunity of the second launch prompts, and
 * the nine after it do not. Written the other way round (increment, then test) the first prompt
 * would land nine opportunities late and every later one would be off by one.
 *
 * [showPrompt] is the platform half — it cannot be called headlessly (Play needs an Activity), so
 * the graph passes a lambda that rings a channel [ReviewPromptHost] answers. Fire-and-forget, like
 * iOS: neither store tells the app whether a prompt was actually shown.
 */
class ReviewPrompter(
    private val launchCount: Property<Int>,
    private val requestCount: Property<Int>,
    private val showPrompt: () -> Unit,
) {
    fun appLaunched() {
        launchCount.value += 1
    }

    fun request() {
        if (launchCount.value <= 1) return
        if (requestCount.value % REQUEST_PERIOD == 0) showPrompt()
        requestCount.value += 1
    }
}
