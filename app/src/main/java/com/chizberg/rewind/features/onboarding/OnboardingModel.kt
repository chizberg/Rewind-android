package com.chizberg.rewind.features.onboarding

import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

/** The first-run onboarding's reducer. Port of iOS `OnboardingViewModel`. */
typealias OnboardingModel = Reducer<OnboardingState, OnboardingAction>

/**
 * Port of iOS `struct OnboardingViewState {}` — the wizard carries no state at all (which page is
 * showing belongs to the pager, as it belongs to iOS's `NavigationStack` path).
 */
data object OnboardingState

/** Port of iOS `OnboardingViewAction`: the single "done" signal the last page's button sends. */
sealed interface OnboardingAction {
    data object OnboardingFinished : OnboardingAction
}

/**
 * The persisted "already seen" flag. Port of iOS `OnboardingStorage`, kept under its own
 * `"onboarding"` key (a separate blob from `SettingsState`, as on iOS).
 */
@Serializable
data class OnboardingStorage(
    val wasShown: Boolean = false,
)

/**
 * Builds the onboarding reducer, or **nothing** if it has already been shown. Port of iOS
 * `makeOnboardingViewModel`, including the `guard !storage.value.wasShown else { return nil }`
 * shape: a null return is what tells the app there is no onboarding layer to present, so the gate
 * lives in one place rather than in a flag the app state has to interpret.
 *
 * Divergence in the dependency, not the logic: iOS takes the whole `KeyValueStorage` and makes its
 * codable field itself; the Android storage primitive (`JsonPreference`) needs a DataStore and so
 * belongs to `AppGraph`, which hands in the synchronous [storage] Property — the same injection
 * `FavoritesModel` already takes.
 *
 * On finish the flag is set and [onFinish] runs, both inside an `effect { }` (iOS runs them inline
 * in the reduce closure; the repo's convention is that side effects leave through `effect`).
 */
fun makeOnboardingViewModel(
    storage: Property<OnboardingStorage>,
    onFinish: () -> Unit,
    scope: CoroutineScope,
): OnboardingModel? {
    if (storage.value.wasShown) return null
    return Reducer(
        initial = OnboardingState,
        scope = scope,
    ) { state, action, effect, _ ->
        when (action) {
            OnboardingAction.OnboardingFinished -> {
                effect {
                    storage.value = storage.value.copy(wasShown = true)
                    onFinish()
                }
                state
            }
        }
    }
}
