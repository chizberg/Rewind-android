package com.chizberg.rewind.features.settings

import com.chizberg.rewind.domain.GradientScheme
import com.chizberg.rewind.domain.ImageSorting
import kotlinx.serialization.Serializable

/**
 * Persisted user settings. Port of iOS `SettingsState` (`Screens/Settings/SettingsViewModel.swift`),
 * stored as one JSON blob under the `"settings"` key.
 *
 * The three fields are exactly iOS's: [openClusterPreviews] (the map's cluster-tap behaviour),
 * [sorting] (edited from an image list's own toolbar menu, not from the settings screen) and
 * [gradientScheme] (the colour-by-year ramp). The map type is NOT here — iOS keeps it in
 * `MapState`, transient per launch.
 *
 * **Every field carries a default** (mirrors iOS `decodeIfPresent … ?? default`): combined with the
 * lenient `ignoreUnknownKeys` codec, that is what lets a field be added without a migration — an
 * old blob missing it decodes to the default, and a newer blob read by an older build ignores the
 * extra. Any field added later must keep this same defaulted shape.
 */
@Serializable
data class SettingsState(
    val openClusterPreviews: Boolean = false,
    val sorting: ImageSorting = ImageSorting.DateAscending,
    val gradientScheme: GradientScheme = GradientScheme.Rewind,
)
