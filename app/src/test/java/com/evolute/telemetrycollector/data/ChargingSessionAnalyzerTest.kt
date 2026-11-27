package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test

class ChargingSessionAnalyzerTest {
    private fun sample(minute: Int, soc: Double, odometer: Double = 1000.0) =
        TelemetrySnapshot("2026-11-26T10:${minute.toString().padStart(2, '0')}:00Z", soc, odometer, null, true, true, "{}")

    @Test fun groupsStationarySocRiseIntoOneSession() {
        val sessions = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 40.0), sample(1, 40.2), sample(2, 40.5), sample(3, 40.8),
            sample(4, 40.8), sample(9, 40.8)
        ))
        assertEquals(1, sessions.size)
        assertEquals(40.0, sessions[0].startSoc, 0.001)
        assertEquals(40.8, sessions[0].endSoc, 0.001)
        assertEquals(0.8, sessions[0].addedSocPercent, 0.001)
        assertEquals(3L, sessions[0].durationMinutes)
    }

    @Test fun accumulatesSmallSocRisesAfterSessionStarts() {
        val sessions = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 40.0),
            sample(1, 40.2),
            sample(2, 40.3),
            sample(3, 40.4),
            sample(4, 40.5),
            sample(5, 40.6),
            sample(10, 40.6)
        ))
        assertEquals(1, sessions.size)
        assertEquals(40.0, sessions[0].startSoc, 0.001)
        assertEquals(40.6, sessions[0].endSoc, 0.001)
        assertEquals(0.6, sessions[0].addedSocPercent, 0.001)
        assertEquals(5L, sessions[0].durationMinutes)
    }

    @Test fun doesNotReportOngoingChargingSessionAsCompleted() {
        val sessions = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 40.0),
            sample(1, 40.5),
            sample(2, 40.7)
        ))
        assertTrue(sessions.isEmpty())
    }

    @Test fun ignoresSocRiseWhileDriving() {
        val sessions = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 40.0, 1000.0), sample(1, 40.5, 1000.5),
            sample(2, 41.0, 1001.0), sample(8, 41.0, 1001.0)
        ))
        assertTrue(sessions.isEmpty())
    }

    @Test fun separatesSessionsAcrossLongTelemetryGap() {
        val sessions = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 40.0), sample(1, 40.4), sample(2, 40.6),
            sample(10, 55.0), sample(11, 55.4), sample(12, 55.7),
            sample(18, 55.7)
        ))
        assertEquals(2, sessions.size)
        assertEquals(0.6, sessions[1].addedSocPercent, 0.001)
        assertEquals(0.7, sessions[0].addedSocPercent, 0.001)
    }

    @Test fun rejectsSmallRiseAndInvalidSoc() {
        val small = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 40.0), sample(1, 40.2), sample(2, 40.3),
            sample(3, 40.3), sample(8, 40.3)
        ))
        assertTrue(small.isEmpty())

        val invalid = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 105.0), sample(1, 105.5), sample(2, 105.8),
            sample(8, 105.8)
        ))
        assertTrue(invalid.isEmpty())
    }

    @Test fun closesSessionWhenSocFalls() {
        val sessions = ChargingSessionAnalyzer.analyze(listOf(
            sample(0, 40.0), sample(1, 40.4), sample(2, 40.7),
            sample(3, 40.5), sample(9, 40.5)
        ))
        assertEquals(1, sessions.size)
        assertEquals(0.7, sessions[0].addedSocPercent, 0.001)
    }
}
