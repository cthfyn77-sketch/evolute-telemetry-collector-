package com.evolute.telemetrycollector.data

/**
 * Samples SOC at each completed whole-kilometre odometer checkpoint.
 * Both range and consumption are based on the latest complete 10 km window.
 */
internal data class RangeMetrics(
    val estimatedRangeKm: Double,
    val consumptionPercentPer100Km: Double
)

internal object RangeEstimator {
    private const val STEP_KM = 1.0
    private const val WINDOW_KM = 10.0
    private const val EPSILON = 0.0001

    fun estimate(history: List<TelemetrySnapshot>): Double? =
        metrics(history)?.estimatedRangeKm

    fun consumptionPer100Km(history: List<TelemetrySnapshot>): Double? =
        metrics(history)?.consumptionPercentPer100Km

    fun metrics(history: List<TelemetrySnapshot>): RangeMetrics? {
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

        if (checkpoints.size < 11) return null
        val start = checkpoints[checkpoints.size - 11]
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
