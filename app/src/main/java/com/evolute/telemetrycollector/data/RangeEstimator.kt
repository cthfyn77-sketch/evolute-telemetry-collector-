package com.evolute.telemetrycollector.data

/**
 * Samples SOC at each completed whole-kilometre odometer checkpoint.
 * The range estimate uses the SOC consumed over the latest complete 10 km
 * (11 consecutive 1-km checkpoints) and the SOC at the latest checkpoint.
 *
 * Telemetry is polled more frequently, so SOC at a kilometre boundary is
 * linearly interpolated between the two surrounding telemetry samples.
 */
internal object RangeEstimator {
    private const val STEP_KM = 1.0
    private const val WINDOW_KM = 10.0
    private const val EPSILON = 0.0001

    fun estimate(history: List<TelemetrySnapshot>): Double? {
        if (history.size < 2) return null

        val ordered = history.sortedBy { it.odometer }
        val checkpoints = mutableListOf<Pair<Double, Double>>()
        var nextCheckpoint = kotlin.math.floor(ordered.first().odometer + EPSILON) + STEP_KM

        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            val distance = current.odometer - previous.odometer
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

        // 11 checkpoints are needed to measure a complete 10-km window.
        if (checkpoints.size < 11) return null
        val start = checkpoints[checkpoints.size - 11]
        val end = checkpoints.last()
        if (end.first - start.first < WINDOW_KM - EPSILON) return null

        val consumedSoc = start.second - end.second
        if (consumedSoc <= EPSILON || !consumedSoc.isFinite()) return null

        val socPerKm = consumedSoc / WINDOW_KM
        val remainingSoc = end.second.coerceIn(0.0, 100.0)
        return (remainingSoc / socPerKm).takeIf { it.isFinite() && it >= 0.0 }
    }
}
