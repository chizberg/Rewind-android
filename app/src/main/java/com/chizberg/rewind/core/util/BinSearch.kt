package com.chizberg.rewind.core.util

/**
 * Index of the first element whose [key] is `>= goal`, or `null` if every element is smaller (or
 * the list is empty). Port of iOS `binSearch(firstEqualOrGreaterThan:in:)`, keeping its
 * non-standard loop invariant (`arr[lhs+1] < arr[rhs]`). [key] projects the comparison value
 * (iOS uses a KeyPath), collapsing the plain and keyed iOS overloads into one.
 */
fun <T> List<T>.binSearchFirstEqualOrGreater(
    goal: Double,
    key: (T) -> Double,
): Int? {
    if (isEmpty()) return null
    if (size == 1) return if (key(this[0]) >= goal) 0 else null
    var lhs = 0
    var rhs = size - 1
    while (key(this[lhs + 1]) < key(this[rhs])) {
        val mid = (lhs + rhs) / 2
        if (key(this[mid]) >= goal) rhs = mid else lhs = mid
    }
    if (key(this[lhs]) >= goal) return lhs
    if (key(this[rhs]) >= goal) return rhs
    return null
}

/** Convenience for a list already holding the comparison keys. */
fun List<Double>.binSearchFirstEqualOrGreater(goal: Double): Int? =
    binSearchFirstEqualOrGreater(goal) { it }
