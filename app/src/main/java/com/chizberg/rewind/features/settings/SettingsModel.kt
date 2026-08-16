package com.chizberg.rewind.features.settings

import com.chizberg.rewind.core.redux.Property
import com.chizberg.rewind.core.redux.Reducer
import com.chizberg.rewind.core.util.Haptics
import com.chizberg.rewind.domain.GradientScheme
import kotlinx.coroutines.CoroutineScope

/** The settings screen's reducer. Port of iOS `SettingsViewModel`. */
typealias SettingsModel = Reducer<SettingsViewState, SettingsAction>

/**
 * The settings screen's state. Port of iOS `SettingsViewState`, minus the three app-icon fields
 * (`supportsAlternateIcons`, `icon`) and the alert that only ever reported an icon failure —
 * alternate app icons are a documented non-port (see CLAUDE.md), so nothing here can fail and the
 * screen needs no alert plumbing at all.
 *
 * What is left is exactly iOS's [stored] blob: the screen edits a copy of it and every change is
 * written straight back out (see [makeSettingsViewModel]).
 */
data class SettingsViewState(
    val stored: SettingsState,
)

/**
 * Port of iOS `SettingsViewAction.UI`. The `iconSelected` / `alert` / `internal(.iconApplied)`
 * branches go with the app-icon picker and are not ported; `viewInAppStore` has no Android
 * counterpart (there is no store listing to link to — see [makeSettingsViewModel]).
 *
 * There is no `.internal` nesting left to mirror, so the actions sit flat.
 */
sealed interface SettingsAction {
    data class SetOpenClusterPreviews(
        val value: Boolean,
    ) : SettingsAction

    data class GradientSchemeSelected(
        val scheme: GradientScheme,
    ) : SettingsAction

    data object Contact : SettingsAction

    data object OpenRepo : SettingsAction

    data object OpenPastVu : SettingsAction

    data object PastVuRules : SettingsAction
}

/** iOS `pastvuCom`, kept next to the actions that open it. */
private const val PASTVU_URL = "https://pastvu.com"

private const val PASTVU_RULES_URL = "https://docs.pastvu.com/en/rules"
private const val CONTACT_URL = "mailto:a.chizberg@proton.me"
private const val REPO_URL = "https://github.com/chizberg/Rewind"

/**
 * Builds the settings reducer. Port of iOS `makeSettingsViewModel`.
 *
 * The screen is opened through a factory, so [settings] is read once at construction and the state
 * starts from whatever is persisted right now (iOS builds a fresh `SettingsViewModel` on every
 * `.present` for the same reason). Every state change is mirrored straight back into [settings] —
 * the whole blob, not a field at a time.
 *
 * Divergence: iOS chains `.onStateUpdate { settings.value = $0.stored }` on the model, which is
 * sound there because ARC drops the store together with the screen. Here [scope] is the app-wide
 * `viewModelScope`, and `onStateUpdate` launches a collector that never completes — every `.present`
 * would leave one more of them (and the model it closes over) alive until the process dies, since
 * `AppAction.Settings.Dismiss` only drops the reference. So the write happens in the two mutating
 * branches' own `effect { }` instead — the same shape `FavoritesModel` already uses to persist
 * through a [Property], with no lifecycle machinery to get wrong.
 *
 * [haptics] carries iOS's `UISelectionFeedbackGenerator().selectionChanged()`, which it constructs
 * inline in `reduce` on a scheme change; the call sits in the same branch here, wrapped in an
 * `effect { }` as every other side effect in this repo is.
 *
 * None of the link actions checks whether anything can open the URL first — neither does iOS, and
 * there is no failure branch to port. (`mailto:` still needs its own `<queries>` entry in the
 * manifest, or the system hides every mail app from the intent resolver and the tap does nothing.)
 */
fun makeSettingsViewModel(
    settings: Property<SettingsState>,
    urlOpener: (String) -> Unit,
    scope: CoroutineScope,
    haptics: Haptics = Haptics.None,
): SettingsModel =
    Reducer<SettingsViewState, SettingsAction>(
        initial = SettingsViewState(stored = settings.value),
        scope = scope,
    ) { state, action, effect, _ ->
        when (action) {
            is SettingsAction.SetOpenClusterPreviews -> {
                val stored = state.stored.copy(openClusterPreviews = action.value)
                effect { settings.value = stored }
                state.copy(stored = stored)
            }

            is SettingsAction.GradientSchemeSelected -> {
                val stored = state.stored.copy(gradientScheme = action.scheme)
                effect { haptics.selection() }
                effect { settings.value = stored }
                state.copy(stored = stored)
            }

            SettingsAction.Contact -> {
                effect { urlOpener(CONTACT_URL) }
                state
            }

            SettingsAction.OpenRepo -> {
                effect { urlOpener(REPO_URL) }
                state
            }

            SettingsAction.OpenPastVu -> {
                effect { urlOpener(PASTVU_URL) }
                state
            }

            SettingsAction.PastVuRules -> {
                effect { urlOpener(PASTVU_RULES_URL) }
                state
            }
        }
    }
