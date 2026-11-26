package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingSessionSummaryTest {
    private fun session(gain: Double, minutes: Long) = ChargingSession(
        startedAt = "2026-11-26T10:00:00Z",
        endedAt = "2026-11-26T10:00:00Z",
        startSoc = 40.0,
        endSoc = 40.0 + gain,
        durationMinutes = minutes
    )

    @Test fun summarizesTotalsAverageAndLargestSession() {
        val summary = ChargingSessionSummary.from(listOf(
            session(12.0, 90),
            session(8.0, 60),
            session(5.0, 30)
        ))
        assertEquals(3, summary.sessionCount)
        assertEquals(25.0, summary.totalAddedSocPercent, 0.001)
        assertEquals(25.0 / 3.0, summary.averageAddedSocPercent, 0.001)
        assertEquals(180L, summary.totalGrowthMinutes)
        assertEquals(12.0, summary.largestSessionGainPercent, 0.001)
    }

    @Test fun emptyHistoryProducesZeroSummary() {
        val summary = ChargingSessionSummary.from(emptyList())
        assertEquals(0, summary.sessionCount)
        assertEquals(0.0, summary.totalAddedSocPercent, 0.001)
        assertEquals(0.0, summary.averageAddedSocPercent, 0.001)
        assertEquals(0L, summary.totalGrowthMinutes)
        assertEquals(0.0, summary.largestSessionGainPercent, 0.001)
    }
}
