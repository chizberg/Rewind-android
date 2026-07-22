package com.chizberg.rewind.domain

import kotlinx.serialization.Serializable

/**
 * Photo shooting direction (8 rhumbs + aero). Port of iOS `Direction`.
 *
 * `@Serializable` for the M10 persistence DTO `StorageImage`.
 */
@Serializable
enum class Direction {
    N,
    E,
    S,
    W,
    NE,
    NW,
    SE,
    SW,
    AERO,
    ;

    /**
     * Marker rotation in degrees clockwise from north — matches Google Maps' `Marker.rotation`
     * convention directly (iOS `Direction.angleDegrees`). `aero` (top-down) has no bearing.
     */
    val angleDegrees: Float?
        get() =
            when (this) {
                N -> 0f
                E -> 90f
                S -> 180f
                W -> 270f
                NE -> 45f
                NW -> 315f
                SE -> 135f
                SW -> 225f
                AERO -> null
            }

    companion object {
        /**
         * Parses a PastVu rhumb string. `null`/empty -> `null`.
         *
         * Divergence from iOS: iOS calls `assertionFailure` for an unknown non-empty string
         * (a debug trap). A JVM domain type must not trap in unit tests, so unknown -> `null`,
         * matching iOS release behavior.
         */
        fun fromString(s: String?): Direction? {
            if (s.isNullOrEmpty()) return null
            return entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
        }
    }
}
