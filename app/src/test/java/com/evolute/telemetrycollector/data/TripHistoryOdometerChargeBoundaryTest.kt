package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TripHistoryOdometerChargeBoundaryTest {
    private fun sample(at: String, soc: Double, odometer: Double, state: String? = null) =
        TelemetrySnapshot(
            at, soc, odometer, null, true, true,
            state?.let { """{"charge_session":{"state":"$it"}}""" } ?: "{}"
        )

    @Test fun odometerRollbackDuringChargingSeparatesPreAndPostResetTrips() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample("2026-11-26T10:00:00Z", 90.0, 1000.0),
            sample("2026-11-26T10:01:00Z", 89.5, 1000.5),
            sample("2026-11-26T10:02:00Z", 89.0, 1001.0),
            sample("2026-11-26T10:10:00Z", 89.0, 1001.0, "active"),
            // Sustained odometer correction while the confirmed charge session is active.
            sample("2026-11-26T10:20:00Z", 94.0, 990.0, "active"),
            sample("2026-11-26T10:25:00Z", 96.0, 990.0, "active"),
            sample("2026-11-26T10:30:00Z", 96.0, 990.0, "inactive"),
            sample("2026-11-26T10:35:00Z", 95.5, 990.5),
            sample("2026-11-26T10:36:00Z", 95.0, 991.0),
            sample("2026-11-26T10:51:00Z", 95.0, 991.0)
        ))

        assertEquals("The sustained counter correction must split trips without losing the later drive: $trips", 2, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:35:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:36:00Z", trips[0].endedAt)
        assertEquals(1.0, trips[1].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[1].startedAt)
        assertEquals("2026-11-26T10:10:00Z", trips[1].endedAt)
    }

    @Test fun repeatedChargeSessionsAfterOdometerRollbackDoNotJoinTrips() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample("2026-11-26T10:00:00Z", 90.0, 1000.0),
            sample("2026-11-26T10:01:00Z", 89.5, 1000.5),
            sample("2026-11-26T10:02:00Z", 89.0, 1001.0),
            sample("2026-11-26T10:10:00Z", 89.0, 1001.0, "active"),
            sample("2026-11-26T10:20:00Z", 94.0, 990.0, "active"),
            sample("2026-11-26T10:25:00Z", 96.0, 990.0, "inactive"),
            sample("2026-11-26T10:30:00Z", 95.5, 990.5),
            sample("2026-11-26T10:31:00Z", 95.0, 991.0),
            sample("2026-11-26T10:32:00Z", 95.0, 991.0, "active"),
            sample("2026-11-26T10:45:00Z", 98.0, 991.0, "inactive"),
            sample("2026-11-26T10:50:00Z", 97.5, 991.5),
            sample("2026-11-26T10:51:00Z", 97.0, 992.0),
            sample("2026-11-26T11:06:00Z", 97.0, 992.0)
        ))

        assertEquals("Each post-reset drive should remain distinct across separate charge sessions: $trips", 2, trips.size)
        assertEquals(2.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:30:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:51:00Z", trips[0].endedAt)
        assertEquals(1.0, trips[1].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[1].startedAt)
        assertEquals("2026-11-26T10:10:00Z", trips[1].endedAt)
    }
}
