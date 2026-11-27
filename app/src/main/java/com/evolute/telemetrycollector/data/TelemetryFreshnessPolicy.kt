package com.evolute.telemetrycollector.data

import java.time.Instant

internal enum class TelemetryFreshness {
    ACCEPT,
    DUPLICATE,
    STALE
}

/**
 * Compares server timestamps, not local polling times.
 * Missing or malformed timestamps are accepted because they cannot safely prove
 * that a snapshot is repeated or out of order.
 */
internal object TelemetryFreshnessPolicy {
    fun classify(incomingUpdatedAt: String?, latestUpdatedAt: String?): TelemetryFreshness {
        val incoming = parse(incomingUpdatedAt) ?: return TelemetryFreshness.ACCEPT
        val latest = parse(latestUpdatedAt) ?: return TelemetryFreshness.ACCEPT
        return when {
            incoming == latest -> TelemetryFreshness.DUPLICATE
            incoming.isBefore(latest) -> TelemetryFreshness.STALE
            else -> TelemetryFreshness.ACCEPT
        }
    }

    private fun parse(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value.trim())
        } catch (_: Exception) {
            null
        }
    }
}
