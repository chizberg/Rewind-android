package com.chizberg.rewind

/**
 * Loads a recorded PastVu API response from test resources (`fixtures/`).
 * Mirrors iOS `Fixture` (RewindTests/Fixtures.swift); the JSON files are byte-identical
 * across both repos.
 */
object Fixture {
    fun text(name: String): String =
        Fixture::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }
}
