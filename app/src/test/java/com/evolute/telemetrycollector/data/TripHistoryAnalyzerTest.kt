package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test

class TripHistoryAnalyzerTest {
    private fun sample(minute: Int, soc: Double, odometer: Double) =
        TelemetrySnapshot("2026-11-26T10:${minute.toString().padStart(2, '0')}:00Z", soc, odometer, null, true, true, "{}")

    @Test fun groupsOdometerMovementIntoTripAndCalculatesSocUse() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.8, 1000.5),
            sample(2, 89.0, 1001.0),
            sample(3, 89.0, 1001.0),
            sample(18, 89.0, 1001.0)
        ))
        assertEquals(1, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals(120L, trips[0].durationSeconds)
        assertEquals(1.0, trips[0].socUsedPercent!!, 0.001)
    }

    @Test fun separatesTripsAfterFifteenMinuteStop() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.6),
            sample(2, 89.0, 1001.0),
            sample(20, 85.0, 1001.0),
            sample(21, 84.8, 1001.5),
            sample(22, 84.0, 1002.0),
            sample(38, 84.0, 1002.0)
        ))
        assertEquals(2, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals(1.0, trips[1].distanceKm, 0.001)
    }

    @Test fun doesNotShowAnOngoingDriveAsCompleted() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.6),
            sample(2, 89.0, 1001.0),
            sample(10, 88.0, 1002.0)
        ))
        assertTrue(trips.isEmpty())
    }

    @Test fun ignoresShortOdometerNoise() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 90.0, 1000.01),
            sample(2, 90.0, 1000.03),
            sample(3, 90.0, 1000.1)
        ))
        assertTrue(trips.isEmpty())
    }
    @Test fun doesNotBuildTripAcrossTelemetryOutage() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.6),
            sample(2, 89.0, 1001.0),
            sample(10, 80.0, 1010.0),
            sample(11, 79.5, 1010.6),
            sample(12, 79.0, 1011.0),
            sample(13, 79.0, 1011.0),
            sample(14, 79.0, 1011.0),
            sample(15, 79.0, 1011.0),
            sample(16, 79.0, 1011.0),
            sample(17, 79.0, 1011.0),
            sample(18, 79.0, 1011.0),
            sample(19, 79.0, 1011.0),
            sample(20, 79.0, 1011.0),
            sample(21, 79.0, 1011.0),
            sample(22, 79.0, 1011.0),
            sample(23, 79.0, 1011.0),
            sample(24, 79.0, 1011.0),
            sample(25, 79.0, 1011.0),
            sample(26, 79.0, 1011.0),
            sample(27, 79.0, 1011.0),
            sample(28, 79.0, 1011.0)
        ))
        assertEquals(1, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals(1.0, trips[0].socUsedPercent!!, 0.001)
        assertEquals("2026-11-26T10:11:00Z", trips[0].startedAt)
    }

}
