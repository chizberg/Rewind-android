package com.chizberg.rewind.domain

/**
 * The base map's style. Port of iOS `MapType` (`Screens/Map/MapType.swift`).
 *
 * JVM-only, like every other domain type: the translation to the SDK's own map type happens at the
 * UI boundary (see `features/map/ui/MapConversions.kt`), exactly as `Coordinate` becomes a `LatLng`
 * only there. It is deliberately NOT part of `SettingsState` — iOS keeps it in `MapState`, so it
 * resets to [Scheme] on every launch and never reaches the settings blob.
 */
enum class MapType {
    Scheme,
    Hybrid,
    ;

    val isScheme: Boolean get() = this == Scheme

    val isHybrid: Boolean get() = this == Hybrid
}
