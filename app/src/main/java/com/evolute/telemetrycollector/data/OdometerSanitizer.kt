package com.evolute.telemetrycollector.data

import java.time.Duration
import java.time.Instant

/**
 * Corrects a single short-lived odometer outlier in derived calculations.
 * Original snapshots remain unchanged in the database and CSV export.
 */
internal object OdometerSanitizer {
    private const val MAX_NEIGHBOR_GAP_MILLIS = 3 * 60 * 1000L
    private const val MIN_SPIKE_DISTANCE_KM = 1.0
    private const val RETURN_TOLERANCE_KM = 0.1

    fun sanitize(history: List<TelemetrySnapshot>): List<TelemetrySnapshot> {
        val ordered = history.mapNotNull { item ->
            try { Instant.parse(item.capturedAt) to item } catch (_: Exception) { null }
        }.sortedBy { it.first }
        if (ordered.size != history.size) return history
        if (ordered.size < 3) return ordered.map { it.second }

        val corrected = ordered.map { it.second }.toMutableList()
        for (i in 1 until ordered.lastIndex) {
            val (beforeAt, before) = ordered[i - 1]
            val (spikeAt, spike) = ordered[i]
            val (afterAt, after) = ordered[i + 1]
            val beforeGap = Duration.between(beforeAt, spikeAt).toMillis()
            val afterGap = Duration.between(spikeAt, afterAt).toMillis()
            if (beforeGap !in 0..MAX_NEIGHBOR_GAP_MILLIS ||
                afterGap !in 0..MAX_NEIGHBOR_GAP_MILLIS) continue

            val jump = spike.odometer - before.odometer
            val returned = after.odometer - before.odometer
            if (kotlin.math.abs(jump) >= MIN_SPIKE_DISTANCE_KM &&
                kotlin.math.abs(returned) <= RETURN_TOLERANCE_KM) {
                // Correct only the implausible odometer value. Keep the rest of
                // this snapshot (especially SOC and charge state) for analytics.
                corrected[i] = spike.copy(odometer = before.odometer)
            }
        }
        return corrected
    }
}
