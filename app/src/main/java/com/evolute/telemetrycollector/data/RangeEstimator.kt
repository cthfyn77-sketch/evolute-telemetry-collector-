package com.evolute.telemetrycollector.data

/**
 * Measures SOC consumption in complete 1-km intervals and averages the latest
 * eight valid intervals. Raw snapshots are never modified.
 */
internal data class RangeMetrics(
    val estimatedRangeKm: Double,
    val consumptionPercentPer100Km: Double
)

internal object RangeEstimator {
    private const val STEP_KM = 1.0
    private const val MAX_VALID_INTERVALS = 8
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

    fun consumptionPer100Km(history: List<TelemetrySnapshot>): Double? =
        metrics(history)?.consumptionPercentPer100Km

    private fun chronological(history: List<TelemetrySnapshot>): List<TelemetrySnapshot> {
        val sanitized = OdometerSanitizer.sanitize(history)
        val parsed = sanitized.mapNotNull { item ->
            try { java.time.Instant.parse(item.capturedAt).toEpochMilli() to item }
            catch (_: Exception) { null }
        }
        return if (parsed.size == sanitized.size && parsed.isNotEmpty()) {
            parsed.sortedBy { it.first }.map { it.second }
        } else history
    }


    /**
     * A brief stationary SOC dip followed by a return to the previous level is
     * treated as a sensor outlier only when EVACONNECT explicitly reports that
     * no charging session is active. Unknown/unavailable charge state remains
     * conservative and follows the normal SOC-rise reset path.
     */
    private fun isRecoveredStationarySocOutlier(
        previous: TelemetrySnapshot,
        current: TelemetrySnapshot,
        baselineDistance: Double,
        baselineSoc: Double
    ): Boolean {
        val chargeState = try {
            org.json.JSONObject(current.sensorsJson)
                .optJSONObject("charge_session")
                ?.optString("state")
        } catch (_: Exception) {
            null
        }
        if (chargeState != "inactive") return false

        val distanceFromBaseline = current.odometer - baselineDistance
        if (!distanceFromBaseline.isFinite() ||
            distanceFromBaseline < -EPSILON ||
            distanceFromBaseline > 0.1 + EPSILON) return false

        val dip = baselineSoc - previous.soc
        val recovery = current.soc - previous.soc
        val returnedToBaseline = kotlin.math.abs(current.soc - baselineSoc) <= CHARGE_RISE_SOC
        if (dip < CHARGE_RISE_SOC || recovery < CHARGE_RISE_SOC || !returnedToBaseline) return false

        return try {
            val elapsed = java.time.Duration.between(
                java.time.Instant.parse(previous.capturedAt),
                java.time.Instant.parse(current.capturedAt)
            ).toMillis()
            elapsed in 0..TELEMETRY_GAP_MILLIS
        } catch (_: Exception) {
            false
        }
    }
\n    fun metrics(history: List<TelemetrySnapshot>): RangeMetrics? {
        if (history.size < 2) return null
        val ordered = chronological(history)
        val first = ordered.first()
        var baselineDistance = first.odometer
        var baselineSoc = first.soc
        var nextCheckpoint = baselineDistance + STEP_KM
        val validIntervalConsumptions = mutableListOf<Double>()

        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            val distance = current.odometer - previous.odometer

            // Keep the last valid rolling window during a telemetry outage.
            // Restart the unfinished interval at the first restored sample so
            // missing distance and SOC consumption are never fabricated.
            if (isTelemetryGap(previous, current)) {
                baselineDistance = current.odometer
                baselineSoc = current.soc
                nextCheckpoint = baselineDistance + STEP_KM
                continue
            }

            // A true odometer rollback starts a new independent distance epoch.
            // Keeping the old checkpoint would otherwise stall sampling until
            // the odometer climbed back to its former value.
            if (distance < -EPSILON) {
                validIntervalConsumptions.clear()
                baselineDistance = current.odometer
                baselineSoc = current.soc
                nextCheckpoint = baselineDistance + STEP_KM
                continue
            }

            // Any meaningful SOC rise resets the partial interval and rolling
            // window. This covers stationary charging and moving regeneration;
            // neither should be blended into a discharge-consumption interval.
            if (current.soc - previous.soc >= CHARGE_RISE_SOC) {
                validIntervalConsumptions.clear()
                baselineDistance = current.odometer
                baselineSoc = current.soc
                nextCheckpoint = baselineDistance + STEP_KM
                continue
            }

            // Ignore parking samples and tiny odometer noise.
            if (distance <= EPSILON) continue

            while (nextCheckpoint <= current.odometer + EPSILON) {
                if (nextCheckpoint > previous.odometer + EPSILON) {
                    val fraction = ((nextCheckpoint - previous.odometer) / distance).coerceIn(0.0, 1.0)
                    val checkpointSoc = previous.soc + (current.soc - previous.soc) * fraction
                    val consumed = baselineSoc - checkpointSoc
                    // Every boundary closes an interval, including invalid intervals.
                    // This prevents a later interval from spanning an SOC rise or reset.
                    if (consumed > EPSILON && consumed.isFinite() &&
                        baselineSoc in 0.0..100.0 && checkpointSoc in 0.0..100.0) {
                        validIntervalConsumptions.add(consumed / STEP_KM)
                        if (validIntervalConsumptions.size > MAX_VALID_INTERVALS) {
                            validIntervalConsumptions.removeAt(0)
                        }
                    }
                    baselineDistance = nextCheckpoint
                    baselineSoc = checkpointSoc
                }
                nextCheckpoint += STEP_KM
            }
        }

        // Keep the vehicle-reported range active until a full eight-interval
        // sample window exists. A shorter average is too sensitive to one km.
        if (validIntervalConsumptions.size < MAX_VALID_INTERVALS) return null
        val averagePerKm = validIntervalConsumptions.average()
        val latestSoc = ordered.last().soc
        if (!averagePerKm.isFinite() || averagePerKm <= EPSILON ||
            !latestSoc.isFinite() || latestSoc !in 0.0..100.0) return null

        val consumptionPer100Km = averagePerKm * 100.0
        val range = latestSoc / averagePerKm
        if (!range.isFinite() || range < 0.0 || !consumptionPer100Km.isFinite()) return null
        return RangeMetrics(range, consumptionPer100Km)
    }
}
