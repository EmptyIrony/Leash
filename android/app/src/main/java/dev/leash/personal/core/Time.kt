package dev.leash.personal.core

import java.time.Instant
import java.time.OffsetDateTime

/** ISO 8601 timestamp ("2026-11-23T01:00:00Z", with or without fractional seconds or an offset) as epoch milliseconds. */
fun parseIsoMillis(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
}

/** Wall clock in epoch milliseconds; the models read it through this so tests can pin it. */
object Clock {
    @Volatile var now: () -> Long = { System.currentTimeMillis() }
}
