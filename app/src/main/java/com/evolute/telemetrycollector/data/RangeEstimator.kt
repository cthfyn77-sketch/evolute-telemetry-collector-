package com.evolute.telemetrycollector.data

/**
 * Uses one-kilometre SOC checkpoints over the latest 5 km of driving.
 * A stationary SOC increase marks charging and resets the estimation window.
 */
internal data class RangeMetrics(
    val estimatedRangeKm: Double,
    val consumptionPercentPer100Km: Double
)

internal object RangeEstimator {
    private const val STEP_KM = 1.0
    private const val WINDOW_KM = 5.0
    private const val EPSILON = 0.0001
    private const val CHARGE_RISE_SOC = 0.2
    private const val TELEMETRY_GAP_MILLIS = 3 * 60 * 1000L

    private fun isTelemetryGap(previous: TelemetrySnapshot, current: TelemetrySnapshot): Boolean {
        return try {
            val elapsed = java.time.Duration.between(
                java.time.Instant.parse(previous.capturedAt), java.time.Instant.parse(current.capturedAt)
            ).toMillis()
            elapsed > TELEMETRY_GAP_MILLIS
        } catch (_: Exception) { false }
    }

    fun estimate(history: List<TelemetrySnapshot>): Double? = metrics(history)?.estimatedRangeKm
    fun consumptionPer100Km(history: List<TelemetrySnapshot>): Double? = metrics(history)?.consumptionPercentPer100Km

    private fun chronological(history: List<TelemetrySnapshot>): List<TelemetrySnapshot> {
        val parsed = OdometerSanitizer.sanitize(history).mapNotNull { item ->
            try { java.time.Instant.parse(item.capturedAt).toEpochMilli() to item } catch (_: Exception) { null }
        }
        return if (parsed.size == history.size && parsed.isNotEmpty()) parsed.sortedBy { it.first }.map { it.second }
        else history
    }

    fun metrics(history: List<TelemetrySnapshot>): RangeMetrics? {
        if (history.size < 2) return null
        val ordered = chronological(history)
        // Keep the first observed SOC as the baseline, so five driven kilometres
        // produce six boundary points (start + km 1..5).
        val checkpoints = mutableListOf(ordered.first().odometer to ordered.first().soc)
        var nextCheckpoint = kotlin.math.floor(ordered.first().odometer + EPSILON) + STEP_KM

        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            val distance = current.odometer - previous.odometer

            // A long collection gap makes interpolation unsafe: the car may have moved
            // while no samples were recorded. Start a fresh window at the first new sample.
            if (isTelemetryGap(previous, current) && distance > EPSILON) {
                checkpoints.clear()
                checkpoints.add(current.odometer to current.soc)
                nextCheckpoint = kotlin.math.floor(current.odometer + EPSILON) + STEP_KM
                continue
            }

            // Charging is identified only when SOC rises while odometer remains stationary.
            // Drop all pre-charge checkpoints so a 5-km window cannot span a charge session.
            if (kotlin.math.abs(distance) <= EPSILON &&
                current.soc - previous.soc >= CHARGE_RISE_SOC) {
                checkpoints.clear()
                // The final SOC observed while parked becomes the new post-charge baseline.
                checkpoints.add(current.odometer to current.soc)
                nextCheckpoint = kotlin.math.floor(current.odometer + EPSILON) + STEP_KM
                continue
            }

            // Ignore stationary samples and odometer reversals; only driving creates checkpoints.
            if (distance <= EPSILON) continue

            while (nextCheckpoint <= current.odometer + EPSILON) {
                if (nextCheckpoint > previous.odometer + EPSILON) {
                    val fraction = ((nextCheckpoint - previous.odometer) / distance).coerceIn(0.0, 1.0)
                    val socAtCheckpoint = previous.soc + (current.soc - previous.soc) * fraction
                    checkpoints.add(nextCheckpoint to socAtCheckpoint)
                }
                nextCheckpoint += STEP_KM
            }
        }

        if (checkpoints.size < 6) return null
        val start = checkpoints[checkpoints.size - 6]
        val end = checkpoints.last()
        if (end.first - start.first < WINDOW_KM - EPSILON) return null
        val consumedSoc = start.second - end.second
        if (consumedSoc <= EPSILON || !consumedSoc.isFinite()) return null
        val remainingSoc = end.second.coerceIn(0.0, 100.0)
        val consumptionPer100Km = consumedSoc / WINDOW_KM * 100.0
        val range = remainingSoc / (consumedSoc / WINDOW_KM)
        if (!range.isFinite() || range < 0.0 || !consumptionPer100Km.isFinite()) return null
        return RangeMetrics(range, consumptionPer100Km)
    }
}
