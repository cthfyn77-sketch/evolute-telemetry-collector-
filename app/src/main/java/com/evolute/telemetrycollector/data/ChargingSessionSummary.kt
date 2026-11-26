package com.evolute.telemetrycollector.data

internal data class ChargingSessionSummary(
    val sessionCount: Int,
    val totalAddedSocPercent: Double,
    val averageAddedSocPercent: Double,
    val totalGrowthMinutes: Long,
    val largestSessionGainPercent: Double
) {
    companion object {
        fun from(sessions: List<ChargingSession>): ChargingSessionSummary {
            if (sessions.isEmpty()) return ChargingSessionSummary(0, 0.0, 0.0, 0L, 0.0)
            val totalAdded = sessions.sumOf { it.addedSocPercent }
            return ChargingSessionSummary(
                sessionCount = sessions.size,
                totalAddedSocPercent = totalAdded,
                averageAddedSocPercent = totalAdded / sessions.size,
                totalGrowthMinutes = sessions.sumOf { it.durationMinutes.coerceAtLeast(0L) },
                largestSessionGainPercent = sessions.maxOf { it.addedSocPercent }
            )
        }
    }
}
