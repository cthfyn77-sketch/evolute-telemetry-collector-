package com.evolute.telemetrycollector.data

import java.time.Duration
import java.time.Instant

internal data class TripSummary(
    val startedAt: String,
    val endedAt: String,
    val distanceKm: Double,
    val durationSeconds: Long,
    val socUsedPercent: Double?
) {
    /** Distance divided by elapsed trip time; null when timestamps provide no duration. */
    val averageSpeedKmh: Double?
        get() = if (durationSeconds > 0 && distanceKm.isFinite() && distanceKm >= 0.0) {
            distanceKm * 3600.0 / durationSeconds
        } else null
}

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
    private const val CHARGE_RESUME_WINDOW_MILLIS = 10 * 60 * 1000L
    // Keep a confirmed charge session through brief endpoint failures, but do not
    // let an unavailable EVACONNECT status hold a trip open forever.
    private const val CHARGE_STATE_UNAVAILABLE_TIMEOUT_MILLIS = STOP_GAP_MILLIS

    private fun chargeSessionState(snapshot: TelemetrySnapshot): String? {
        // Parse the charge_session object first, then its state. Keeping the
        // match scoped avoids accidentally reading an unrelated "state" field.
        val sessionBody = Regex("\\\"charge_session\\\"\\s*:\\s*\\{([^}]*)\\}")
            .find(snapshot.sensorsJson)?.groupValues?.getOrNull(1) ?: return null
        return Regex("\\\"state\\\"\\s*:\\s*\\\"(active|inactive|unavailable)\\\"")
            .find(sessionBody)?.groupValues?.getOrNull(1)
    }

    fun analyze(history: List<TelemetrySnapshot>): List<TripSummary> {
        if (history.size < 2) return emptyList()
        val ordered = OdometerSanitizer.sanitize(history).mapNotNull { item ->
            try { Instant.parse(item.capturedAt).toEpochMilli() to item } catch (_: Exception) { null }
        }.sortedBy { it.first }
        if (ordered.size < 2) return emptyList()

        val trips = mutableListOf<TripSummary>()
        var active: MutableList<TelemetrySnapshot>? = null
        var lastMovementAt = 0L
        var charging = false
        var chargeEndedAt: Long? = null
        var chargeUnavailableSince: Long? = null

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
            val duration = Duration.between(startTime, endTime).seconds
            if (duration <= 0L) return
            // SOC endpoints must each be valid; a plausible difference between two
            // out-of-range readings is not a trustworthy consumption measurement.
            // A trip's endpoint delta is not reliable if any intermediate SOC
            // sample is invalid or shows a meaningful rise (e.g. charging while
            // parked, a sensor reset, or inconsistent telemetry). Keep the trip,
            // but omit its SOC consumption from statistics.
            val socSequenceReliable = samples.all { it.soc.isFinite() && it.soc in 0.0..100.0 } &&
                samples.zipWithNext().none { (before, after) ->
                    after.soc - before.soc >= 0.2
                }
            val socUsed = if (socSequenceReliable) {
                (start.soc - end.soc).takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }
            } else null
            trips.add(TripSummary(start.capturedAt, end.capturedAt, distance, duration, socUsed))
        }

        for (index in 1 until ordered.size) {
            val (previousAt, previous) = ordered[index - 1]
            val (currentAt, current) = ordered[index]

            val gapMillis = currentAt - previousAt

            // A missing telemetry window is also evidence that charge state may
            // have gone stale. Do not let a previously active session bridge a
            // long API outage and accidentally join drives on either side of it.
            if (charging && gapMillis > CHARGE_STATE_UNAVAILABLE_TIMEOUT_MILLIS) {
                charging = false
                chargeEndedAt = previousAt
                chargeUnavailableSince = null
            }

            val wasCharging = charging
            val chargeState = chargeSessionState(current)
            if (chargeState == "active") {
                chargeUnavailableSince = null
                if (active != null &&
                    (charging || currentAt - lastMovementAt <= STOP_GAP_MILLIS)) {
                    // Charging started within the normal 15-minute stop grace period:
                    // keep the current trip open while the vehicle is plugged in.
                    charging = true
                    chargeEndedAt = null
                }
            } else if (chargeState == "inactive") {
                chargeUnavailableSince = null
                if (charging) {
                    charging = false
                    chargeEndedAt = currentAt
                }
            } else if (chargeState == "unavailable" && charging) {
                // A short endpoint outage must not end a confirmed charge session.
                // If the status remains unavailable for 15 minutes, stop treating
                // the session as active and close the trip from the last known
                // charging sample rather than holding it open indefinitely.
                val unavailableSince = chargeUnavailableSince ?: currentAt.also {
                    chargeUnavailableSince = it
                }
                if (currentAt - unavailableSince > CHARGE_STATE_UNAVAILABLE_TIMEOUT_MILLIS) {
                    charging = false
                    chargeEndedAt = unavailableSince
                    chargeUnavailableSince = null
                }
            }

            // Once charging ends, allow up to 10 minutes for the next drive to
            // begin. If it does not, close the previous trip before any later drive.
            val resumeDeadline = chargeEndedAt
            if (!charging && resumeDeadline != null &&
                currentAt - resumeDeadline > CHARGE_RESUME_WINDOW_MILLIS) {
                finishTrip()
                lastMovementAt = 0L
                chargeEndedAt = null
            }

            // A long stationary gap normally closes a trip, except while a
            // confirmed charge session is active or inside its post-charge grace.
            if (gapMillis >= STOP_GAP_MILLIS && !charging && chargeEndedAt == null) {
                finishTrip()
                lastMovementAt = 0L
                continue
            }
            // A shorter but unusually long gap (over 3 minutes) may be an outage:
            // discard the incomplete segment if movement occurred while offline, or
            // if the recovered sample is unchanged in both odometer and SOC.
            if (gapMillis > TELEMETRY_GAP_MILLIS && !charging && chargeEndedAt == null) {
                val recoveredMovement = current.odometer - previous.odometer
                val recoveredSocChange = kotlin.math.abs(current.soc - previous.soc)
                if (recoveredMovement > MOVEMENT_EPSILON_KM ||
                    !recoveredSocChange.isFinite() || recoveredSocChange <= 0.1) {
                    active = null
                } else {
                    finishTrip()
                }
                lastMovementAt = 0L
                continue
            }

            val delta = current.odometer - previous.odometer
            // A meaningful backwards odometer change is a counter reset/correction,
            // not negative distance. Close the prior segment and use the lower
            // reading as a fresh baseline for any subsequent movement.
            if (delta < -MOVEMENT_EPSILON_KM) {
                finishTrip()
                lastMovementAt = 0L
                continue
            }

            if (active != null && currentAt - lastMovementAt >= STOP_GAP_MILLIS &&
                !charging && chargeEndedAt == null) finishTrip()

            if (delta > MOVEMENT_EPSILON_KM) {
                if (active == null) active = mutableListOf(previous, current)
                else active?.add(current)
                lastMovementAt = currentAt
                if (chargeEndedAt != null &&
                    currentAt - chargeEndedAt!! <= CHARGE_RESUME_WINDOW_MILLIS) {
                    // Movement resumed within the post-charge grace period.
                    chargeEndedAt = null
                }
            } else if (active != null && (charging || wasCharging)) {
                // Include every sample observed during a confirmed charge session,
                // including the final inactive sample and brief unavailable periods.
                // Otherwise a temporary SOC rise could be missed and consumption
                // would be reported from an incomplete sequence.
                active?.add(current)
            } else if (active != null && currentAt - lastMovementAt >= STOP_GAP_MILLIS &&
                !charging && chargeEndedAt == null) {
                finishTrip()
            }
        }
        if (active != null && !charging && chargeEndedAt == null &&
            ordered.last().first - lastMovementAt >= STOP_GAP_MILLIS) finishTrip()
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
    val averageSpeedKmh: Double?,
    val consumptionPoints: List<TripConsumptionPoint>
)

/** Calculates trip consumption only from completed trips with at least 1 km and valid SOC decrease. */
internal object TripConsumptionAnalyzer {
    fun summarize(trips: List<TripSummary>): TripHistoryStats {
        val points = trips.mapNotNull { trip ->
            val soc = trip.socUsedPercent ?: return@mapNotNull null
            if (trip.durationSeconds <= 0L || !trip.distanceKm.isFinite() || trip.distanceKm < 1.0 ||
                !soc.isFinite() || soc <= 0.0 || soc > 100.0) return@mapNotNull null
            val rate = soc / trip.distanceKm * 100.0
            if (!rate.isFinite()) return@mapNotNull null
            TripConsumptionPoint(trip.startedAt, trip.distanceKm, soc, rate)
        }
        val measuredDistance = points.sumOf { it.distanceKm }
        val measuredSoc = points.sumOf { it.socUsedPercent }
        val average = if (measuredDistance > 0.0) measuredSoc / measuredDistance * 100.0 else null
        // Aggregate speed is distance-weighted over elapsed time for completed trips.
        val timedTrips = trips.filter { it.durationSeconds > 0 && it.distanceKm.isFinite() && it.distanceKm >= 0.0 }
        val elapsedSeconds = timedTrips.sumOf { it.durationSeconds }
        val averageSpeed = if (elapsedSeconds > 0L) {
            timedTrips.sumOf { it.distanceKm } * 3600.0 / elapsedSeconds
        } else null
        return TripHistoryStats(
            tripCount = trips.size,
            totalDistanceKm = trips.sumOf { it.distanceKm.coerceAtLeast(0.0) },
            measuredTrips = points.size,
            totalSocUsedPercent = measuredSoc,
            averagePercentPer100Km = average,
            averageSpeedKmh = averageSpeed,
            consumptionPoints = points
        )
    }
}
