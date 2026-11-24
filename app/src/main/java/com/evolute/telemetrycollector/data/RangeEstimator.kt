package com.evolute.telemetrycollector.data

/**
 * Builds SOC checkpoints every 5 km and estimates range from the average SOC
 * consumption over the latest complete 10 km (two 5-km intervals).
 *
 * The estimate changes only when a new 5-km checkpoint is completed.
 */
internal object RangeEstimator {
    private const val STEP_KM = 5.0
    private const val WINDOW_KM = 10.0
    private const val EPSILON = 0.0001

    fun estimate(history: List<TelemetrySnapshot>): Double? {
        if (history.size < 2) return null

        val ordered = history.sortedBy { it.odometer }
        val checkpoints = mutableListOf<Pair<Double, Double>>()
        var nextCheckpoint = kotlin.math.floor(ordered.first().odometer / STEP_KM + EPSILON) *
            STEP_KM + STEP_KM

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

        if (checkpoints.size < 3) return null
        val end = checkpoints.last()
        val start = checkpoints[checkpoints.size - 3]
        if (end.first - start.first < WINDOW_KM - EPSILON) return null

        val consumedSoc = start.second - end.second
        if (consumedSoc <= EPSILON || !consumedSoc.isFinite()) return null

        val socPerKm = consumedSoc / WINDOW_KM
        val remainingSoc = end.second.coerceIn(0.0, 100.0)
        return (remainingSoc / socPerKm).takeIf { it.isFinite() && it >= 0.0 }
    }
}
