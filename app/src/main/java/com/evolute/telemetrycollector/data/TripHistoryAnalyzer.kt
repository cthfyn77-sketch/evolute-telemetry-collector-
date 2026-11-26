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
    private const val TELEMETRY_GAP_MILLIS = 3 * 60 * 1000L

    fun analyze(history: List<TelemetrySnapshot>): List<TripSummary> {
        if (history.size < 2) return emptyList()
        val ordered = OdometerSanitizer.sanitize(history).mapNotNull { item ->
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

            // Never infer a precise trip segment or SOC consumption across a telemetry outage.
            // Discard the incomplete segment and use the recovered sample as a new baseline.
            if (currentAt - previousAt > TELEMETRY_GAP_MILLIS &&
                current.odometer - previous.odometer > MOVEMENT_EPSILON_KM) {
                active = null
                lastMovementAt = 0L
                continue
            }

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
        if (active != null && ordered.last().first - lastMovementAt >= STOP_GAP_MILLIS) finishTrip()
        return trips.sortedByDescending {
            try { Instant.parse(it.startedAt) } catch (_: Exception) { Instant.EPOCH }
        }
    }
}


internal data class TripConsumptionPoint(
    val startedAt: String,
    val distanceKm: Double,
    val socUsedPercent: Double,
    val percentPer100Km: Double
)

internal data class TripHistoryStats(
    val tripCount: Int,
    val totalDistanceKm: Double,
    val measuredTrips: Int,
    val totalSocUsedPercent: Double,
    val averagePercentPer100Km: Double?,
    val consumptionPoints: List<TripConsumptionPoint>
)

/** Calculates trip consumption only from completed trips with at least 1 km and valid SOC decrease. */
internal object TripConsumptionAnalyzer {
    fun summarize(trips: List<TripSummary>): TripHistoryStats {
        val points = trips.mapNotNull { trip ->
            val soc = trip.socUsedPercent ?: return@mapNotNull null
            if (!trip.distanceKm.isFinite() || trip.distanceKm < 1.0 ||
                !soc.isFinite() || soc <= 0.0 || soc > 100.0) return@mapNotNull null
            val rate = soc / trip.distanceKm * 100.0
            if (!rate.isFinite()) return@mapNotNull null
            TripConsumptionPoint(trip.startedAt, trip.distanceKm, soc, rate)
        }
        val measuredDistance = points.sumOf { it.distanceKm }
        val measuredSoc = points.sumOf { it.socUsedPercent }
        val average = if (measuredDistance > 0.0) measuredSoc / measuredDistance * 100.0 else null
        return TripHistoryStats(
            tripCount = trips.size,
            totalDistanceKm = trips.sumOf { it.distanceKm.coerceAtLeast(0.0) },
            measuredTrips = points.size,
            totalSocUsedPercent = measuredSoc,
            averagePercentPer100Km = average,
            consumptionPoints = points
        )
    }
}
