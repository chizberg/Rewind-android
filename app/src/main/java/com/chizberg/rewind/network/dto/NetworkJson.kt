package com.chizberg.rewind.network.dto

import kotlinx.serialization.json.Json

/**
 * Shared JSON decoder for PastVu DTOs. `ignoreUnknownKeys` lets the lean DTOs above
 * decode responses that carry many extra fields (`__v`, `rid`, `y`, view counts, ...).
 */
internal val networkJson = Json { ignoreUnknownKeys = true }
