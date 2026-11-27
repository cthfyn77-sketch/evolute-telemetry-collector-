package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TripHistoryChargeRecoveryTest {
    private fun sample(at: String, soc: Double, odometer: Double, state: String? = null) =
        TelemetrySnapshot(
            at, soc, odometer, null, true, true,
            state?.let { """{"charge_session":{"state":"$it"}}""" } ?: "{}"
        )

    @Test fun repeatedShortUnavailableBurstsDoNotEndConfirmedChargeSession() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample("2026-11-26T10:00:00Z", 90.0, 1000.0),
            sample("2026-11-26T10:01:00Z", 89.5, 1000.5),
            sample("2026-11-26T10:02:00Z", 89.0, 1001.0),
            sample("2026-11-26T10:10:00Z", 89.0, 1001.0, "active"),
            sample("2026-11-26T10:12:00Z", 94.0, 1001.0, "unavailable"),
            sample("2026-11-26T10:14:00Z", 94.0, 1001.0, "active"),
            sample("2026-11-26T10:16:00Z", 94.0, 1001.0, "unavailable"),
            sample("2026-11-26T10:18:00Z", 94.0, 1001.0, "active"),
            sample("2026-11-26T10:20:00Z", 94.0, 1001.0, "inactive"),
            sample("2026-11-26T10:25:00Z", 93.5, 1001.5),
            sample("2026-11-26T10:26:00Z", 93.0, 1002.0),
            sample("2026-11-26T10:41:00Z", 93.0, 1002.0)
        ))

        assertEquals("Repeated brief endpoint failures must not split the trip: $trips", 1, trips.size)
        assertEquals(2.0, trips.single().distanceKm, 0.001)
        assertEquals("2026-11-26T10:26:00Z", trips.single().endedAt)
    }

    @Test fun unavailableChargeStateExpiresOnlyAfterFifteenMinutesAndOneMillisecond() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample("2026-11-26T10:00:00Z", 90.0, 1000.0),
            sample("2026-11-26T10:01:00Z", 89.5, 1000.5),
            sample("2026-11-26T10:02:00Z", 89.0, 1001.0),
            sample("2026-11-26T10:10:00Z", 89.0, 1001.0, "active"),
            sample("2026-11-26T10:12:00Z", 94.0, 1001.0, "unavailable"),
            sample("2026-11-26T10:27:00Z", 94.0, 1001.0, "unavailable"),
            // One millisecond beyond the timeout must expire the stale session.
            sample("2026-11-26T10:27:00.001Z", 94.0, 1001.0, "unavailable"),
            sample("2026-11-26T10:28:00Z", 93.5, 1001.5),
            sample("2026-11-26T10:29:00Z", 93.0, 1002.0),
            sample("2026-11-26T10:44:00Z", 93.0, 1002.0)
        ))

        assertEquals("A charge status unavailable for more than 15 minutes must not bridge trips: $trips", 2, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:27:00.001Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:29:00Z", trips[0].endedAt)
        assertEquals(1.0, trips[1].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[1].startedAt)
        assertEquals("2026-11-26T10:27:00Z", trips[1].endedAt)
    }
}
