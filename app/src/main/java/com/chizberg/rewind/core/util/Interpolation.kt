package com.chizberg.rewind.core.util

/** A value that can be linearly interpolated. Port of iOS `Interpolatable`. */
interface Interpolatable<T> {
    fun lerp(
        at: Double,
        lhs: T,
        rhs: T,
    ): T
}

/** A gradient stop: a [value] pinned at a [position] on the `[0, 1]` axis. Port of iOS `InterpolationPoint`. */
data class InterpolationPoint<T>(
    val position: Double,
    val value: T,
)

/**
 * Normalizes [value] into `[0, 1]` across `[lowerBound, upperBound]`, clamped at both ends.
 * Port of iOS `lerpParameter`: at/below the lower bound -> 0, at/above the upper bound -> 1.
 */
fun lerpParameter(
    value: Double,
    lowerBound: Double,
    upperBound: Double,
): Double {
    if (value <= lowerBound) return 0.0
    if (value >= upperBound) return 1.0
    return (value - lowerBound) / (upperBound - lowerBound)
}

/** Scalar linear interpolation. Port of the VGSL `lerp(at:beetween:)` primitive. */
fun lerp(
    at: Double,
    lhs: Double,
    rhs: Double,
): Double = lhs + (rhs - lhs) * at

/**
 * Interpolates within a sorted list of stops. Port of iOS `lerp(at:in:)`: clamp [rawT] into the
 * stops' position range, binary-search the enclosing segment, then interpolate between its ends.
 * [values] must be non-empty (iOS enforces this via `NonEmptyArray`; here it's a runtime check).
 */
fun <T : Interpolatable<T>> lerp(
    rawT: Double,
    values: List<InterpolationPoint<T>>,
): T {
    require(values.isNotEmpty()) { "interpolation needs at least one stop" }
    val t = rawT.coerceIn(values.first().position, values.last().position)
    val index = values.binSearchFirstEqualOrGreater(t) { it.position } ?: return values.last().value
    if (index == 0) return values.first().value
    val lower = values[index - 1]
    val upper = values[index]
    val t1 = lerpParameter(t, lower.position, upper.position)
    return lower.value.lerp(t1, lower.value, upper.value)
}
