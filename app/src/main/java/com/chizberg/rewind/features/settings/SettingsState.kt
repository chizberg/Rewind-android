package com.chizberg.rewind.features.settings

import com.chizberg.rewind.domain.ImageSorting
import kotlinx.serialization.Serializable

/**
 * Persisted user settings. Port of iOS `SettingsState` (`Screens/Settings/SettingsViewModel.swift`),
 * stored as one JSON blob under the `"settings"` key.
 *
 * Only [sorting] exists so far — it is the one setting M10 needs to survive a relaunch (the list's
 * sort menu writes it, the map shares the order). The full model (map type, gradient scheme,
 * `openClusterPreviews`, …) and the Settings screen that edits the rest land in M13.
 *
 * **Every field carries a default** (mirrors iOS `decodeIfPresent … ?? default`): combined with the
 * lenient `ignoreUnknownKeys` codec, that is what lets M13 add fields without a migration — an old
 * blob missing them decodes to the defaults, and a newer blob read by an older build ignores the
 * extras. So the fields M13 introduces must keep this same defaulted shape.
 */
@Serializable
data class SettingsState(
    val sorting: ImageSorting = ImageSorting.DateAscending,
)
