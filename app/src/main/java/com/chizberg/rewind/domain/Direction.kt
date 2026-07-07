package com.chizberg.rewind.domain

/**
 * Photo shooting direction (8 rhumbs + aero). Port of iOS `Direction`.
 * `angleDegrees` (used for marker rotation) is added in M7, when rendering needs it.
 */
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
