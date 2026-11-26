package com.evolute.telemetrycollector.data

import java.time.Duration
import java.time.Instant

internal data class ChargingSession(
    val startedAt: String,
    val endedAt: String,
    val startSoc: Double,
    val endSoc: Double,
    val durationMinutes: Long
) {
    val addedSocPercent: Double get() = endSoc - startSoc
}

/**
 * Reconstructs charging sessions from local telemetry snapshots.
 * Only valid SOC rises while the odometer is effectively stationary are used.
 * A telemetry gap, vehicle movement, or SOC decrease closes the current session.
 */
internal object ChargingSessionAnalyzer {
    private const val MIN_SOC_RISE = 0.2
    private const val MIN_SESSION_GAIN = 0.5
    private const val ODOMETER_MOVEMENT_KM = 0.1
    private const val MAX_SAMPLE_GAP_MILLIS = 5 * 60 * 1000L

    fun analyze(history: List<TelemetrySnapshot>): List<ChargingSession> {
        if (history.size < 2) return emptyList()
        val ordered = OdometerSanitizer.sanitize(history).mapNotNull { item ->
            val time = try { Instant.parse(item.capturedAt).toEpochMilli() } catch (_: Exception) { null }
            time?.let { it to item }
        }.sortedBy { it.first }
        if (ordered.size < 2) return emptyList()

        val sessions = mutableListOf<ChargingSession>()
        var start: TelemetrySnapshot? = null
        var latest: TelemetrySnapshot? = null

        fun finish() {
            val first = start
            val last = latest
            start = null
            latest = null
            if (first == null || last == null) return
            val gain = last.soc - first.soc
            if (!gain.isFinite() || gain < MIN_SESSION_GAIN) return
            val duration = try {
                Duration.between(Instant.parse(first.capturedAt), Instant.parse(last.capturedAt)).toMinutes()
            } catch (_: Exception) { return }
            sessions += ChargingSession(first.capturedAt, last.capturedAt, first.soc, last.soc, duration.coerceAtLeast(0))
        }

        for (index in 1 until ordered.size) {
            val (previousAt, previous) = ordered[index - 1]
            val (currentAt, current) = ordered[index]
            val gap = currentAt - previousAt
            val validSoc = previous.soc.isFinite() && current.soc.isFinite() &&
                previous.soc in 0.0..100.0 && current.soc in 0.0..100.0
            val stationary = (current.odometer - previous.odometer).let {
                it.isFinite() && kotlin.math.abs(it) <= ODOMETER_MOVEMENT_KM
            }
            if (gap <= 0L || gap > MAX_SAMPLE_GAP_MILLIS || !validSoc || !stationary) {
                finish()
                continue
            }

            val rise = current.soc - previous.soc
            if (rise >= MIN_SOC_RISE) {
                if (start == null) start = previous
                latest = current
            } else if (start != null) {
                if (rise < -MIN_SOC_RISE) finish()
            }
        }
        finish()
        return sessions.sortedByDescending {
            try { Instant.parse(it.startedAt) } catch (_: Exception) { Instant.EPOCH }
        }
    }
}
