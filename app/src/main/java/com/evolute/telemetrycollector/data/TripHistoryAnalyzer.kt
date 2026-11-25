package com.evolute.telemetrycollector.data

import java.time.Duration
import java.time.Instant

internal data class TripSummary(
    val startedAt: String,
    val endedAt: String,
    val distanceKm: Double,
    val durationSeconds: Long,
    val socUsedPercent: Double?
)

/**
 * Reconstructs completed drives from recorded odometer movement.
 * A 15-minute period without movement closes a trip. Short odometer noise
 * is ignored; only trips of at least 0.5 km are shown.
 */
internal object TripHistoryAnalyzer {
    private const val MOVEMENT_EPSILON_KM = 0.02
    private const val MIN_TRIP_DISTANCE_KM = 0.5
    private const val STOP_GAP_MILLIS = 15 * 60 * 1000L

    fun analyze(history: List<TelemetrySnapshot>): List<TripSummary> {
        if (history.size < 2) return emptyList()
        val ordered = history.mapNotNull { item ->
            try { Instant.parse(item.capturedAt).toEpochMilli() to item } catch (_: Exception) { null }
        }.sortedBy { it.first }
        if (ordered.size < 2) return emptyList()

        val trips = mutableListOf<TripSummary>()
        var active: MutableList<TelemetrySnapshot>? = null
        var lastMovementAt = 0L

        fun finishTrip() {
            val samples = active ?: return
            active = null
            if (samples.size < 2) return
            val start = samples.first()
            val end = samples.last()
            val distance = end.odometer - start.odometer
            if (!distance.isFinite() || distance < MIN_TRIP_DISTANCE_KM) return
            val startTime = try { Instant.parse(start.capturedAt) } catch (_: Exception) { return }
            val endTime = try { Instant.parse(end.capturedAt) } catch (_: Exception) { return }
            val duration = Duration.between(startTime, endTime).seconds.coerceAtLeast(0)
            val socUsed = (start.soc - end.soc).takeIf { it.isFinite() && it in 0.0..100.0 }
            trips.add(TripSummary(start.capturedAt, end.capturedAt, distance, duration, socUsed))
        }

        for (index in 1 until ordered.size) {
            val (previousAt, previous) = ordered[index - 1]
            val (currentAt, current) = ordered[index]
            if (active != null && currentAt - lastMovementAt >= STOP_GAP_MILLIS) finishTrip()

            val delta = current.odometer - previous.odometer
            if (delta > MOVEMENT_EPSILON_KM) {
                if (active == null) active = mutableListOf(previous, current)
                else active?.add(current)
                lastMovementAt = currentAt
            } else if (active != null && currentAt - lastMovementAt >= STOP_GAP_MILLIS) {
                finishTrip()
            }
        }
        finishTrip()
        return trips.sortedByDescending {
            try { Instant.parse(it.startedAt) } catch (_: Exception) { Instant.EPOCH }
        }
    }
}
