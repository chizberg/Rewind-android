package com.chizberg.rewind.domain

/**
 * Whether Google has a Street View panorama for a coordinate, and when it was shot. Port of iOS
 * `StreetViewAvailability` (declared next to its factory in `StreetViewFactory.swift`; here it is a
 * plain domain type, since the panorama itself is a native view and has no factory of its own).
 *
 * The metadata endpoint distinguishes seven statuses; every one of them that is not `OK` collapses
 * into [Unavailable] — an exhausted quota and "there is no panorama here" look the same to the user
 * (see `network/Request.kt`).
 */
sealed interface StreetViewAvailability {
    data class Available(
        val year: Int,
    ) : StreetViewAvailability

    data object Unavailable : StreetViewAvailability
}
