package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test

class TripHistoryAnalyzerTest {
    private fun sample(minute: Int, soc: Double, odometer: Double) =
        TelemetrySnapshot("2026-11-26T10:${minute.toString().padStart(2, '0')}:00Z", soc, odometer, null, true, true, "{}")

    private fun chargeSample(minute: Int, soc: Double, odometer: Double, state: String) =
        TelemetrySnapshot(
            "2026-11-26T10:${minute.toString().padStart(2, '0')}:00Z",
            soc, odometer, null, true, true,
            """{"charge_session":{"state":"$state"}}"""
        )

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
        assertEquals(30.0, trips[0].averageSpeedKmh!!, 0.001)
        assertEquals(1.0, trips[0].socUsedPercent!!, 0.001)
    }

    @Test fun keepsTripOpenAcrossChargeAndJoinsMovementWithinTenMinutes() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.5),
            sample(2, 89.0, 1001.0),
            chargeSample(10, 89.0, 1001.0, "active"), // charging starts within 15 minutes
            chargeSample(25, 94.0, 1001.0, "active"), // long charge; trip stays open
            chargeSample(30, 94.0, 1001.0, "inactive"),
            sample(35, 93.5, 1001.5), // movement resumes 5 minutes after charging
            sample(36, 93.0, 1002.0),
            sample(51, 93.0, 1002.0)
        ))

        println("CHARGE_RESUME_DIAGNOSTIC trips=$trips")
        assertEquals("Unexpected trips after charge/resume: $trips", 1, trips.size)
        assertEquals(2.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:36:00Z", trips[0].endedAt)
        assertNull("SOC rise during charging must invalidate trip consumption", trips[0].socUsedPercent)
    }

    @Test fun closesTripTenMinutesAfterChargeWhenMovementDoesNotResume() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.5),
            sample(2, 89.0, 1001.0),
            chargeSample(10, 89.0, 1001.0, "active"),
            chargeSample(25, 94.0, 1001.0, "active"),
            chargeSample(30, 94.0, 1001.0, "inactive"),
            sample(41, 94.0, 1001.0),
            sample(42, 94.0, 1001.0),
            sample(43, 94.0, 1001.0)
        ))

        println("CHARGE_TIMEOUT_DIAGNOSTIC trips=$trips")
        assertEquals(1, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals("Unexpected trip end after charge timeout: $trips", "2026-11-26T10:25:00Z", trips[0].endedAt)
    }

    @Test fun joinsMovementExactlyTenMinutesAfterChargingEnds() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.5),
            sample(2, 89.0, 1001.0),
            chargeSample(10, 89.0, 1001.0, "active"),
            chargeSample(25, 94.0, 1001.0, "active"),
            chargeSample(30, 94.0, 1001.0, "inactive"),
            sample(40, 93.5, 1001.5), // exactly ten minutes after charging ended
            sample(41, 93.0, 1002.0),
            sample(56, 93.0, 1002.0)
        ))

        assertEquals("Movement at the exact ten-minute boundary must resume the trip: $trips", 1, trips.size)
        assertEquals(2.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:41:00Z", trips[0].endedAt)
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

    @Test fun sortsOutOfOrderTelemetryBeforeReconstructingTrips() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(2, 89.0, 1001.0),
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.5),
            sample(3, 89.0, 1001.0),
            sample(18, 89.0, 1001.0)
        ))

        assertEquals(1, trips.size)
        assertEquals("2026-11-26T10:00:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:02:00Z", trips[0].endedAt)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals(1.0, trips[0].socUsedPercent!!, 0.001)
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
        assertEquals("2026-11-26T10:10:00Z", trips[0].startedAt)
    }


    @Test fun doesNotJoinTripAcrossOutageWhenRecoveredOdometerIsUnchanged() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.6),
            sample(2, 89.0, 1001.0),
            // Telemetry returns after an outage, but odometer has not yet changed.
            sample(10, 89.0, 1001.0),
            sample(11, 88.5, 1001.5),
            sample(12, 88.0, 1002.0),
            sample(13, 88.0, 1002.0),
            sample(28, 88.0, 1002.0)
        ))

        assertEquals(1, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals(1.0, trips[0].socUsedPercent!!, 0.001)
        assertEquals("2026-11-26T10:10:00Z", trips[0].startedAt)
    }

    @Test fun telemetryGapOfExactlyThreeMinutesDoesNotSplitTrip() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.5),
            sample(4, 89.0, 1001.0), // exactly three minutes after previous sample
            sample(5, 88.5, 1001.5),
            sample(20, 88.5, 1001.5)
        ))

        assertEquals(1, trips.size)
        assertEquals(1.5, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:05:00Z", trips[0].endedAt)
    }

    @Test fun telemetryGapJustOverThreeMinutesDiscardsIncompleteSegment() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            TelemetrySnapshot("2026-11-26T10:00:00Z", 90.0, 1000.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:01:00Z", 89.5, 1000.5, null, true, true, "{}"),
            // 3 minutes and 1 millisecond after the previous sample: outage boundary.
            TelemetrySnapshot("2026-11-26T10:04:00.001Z", 89.0, 1001.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:05:00.001Z", 88.5, 1001.5, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:20:00.001Z", 88.5, 1001.5, null, true, true, "{}")
        ))

        assertEquals(1, trips.size)
        assertEquals(0.5, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:04:00.001Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:05:00.001Z", trips[0].endedAt)
    }

    @Test fun exactFifteenMinuteStationaryGapSplitsTrips() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            TelemetrySnapshot("2026-11-26T10:00:00Z", 90.0, 1000.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:01:00Z", 89.5, 1000.5, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:02:00Z", 89.0, 1001.0, null, true, true, "{}"),
            // Exactly 15 minutes after the last movement closes the first trip.
            TelemetrySnapshot("2026-11-26T10:17:00Z", 89.0, 1001.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:18:00Z", 88.5, 1001.5, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:19:00Z", 88.0, 1002.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:34:00Z", 88.0, 1002.0, null, true, true, "{}")
        ))

        assertEquals(2, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:17:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:19:00Z", trips[0].endedAt)
        assertEquals(1.0, trips[1].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[1].startedAt)
        assertEquals("2026-11-26T10:02:00Z", trips[1].endedAt)
    }

    @Test fun odometerRollbackClosesCurrentTripAndStartsFreshBaseline() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.5),
            sample(2, 89.0, 1001.0),
            // Odometer is corrected backwards and then continues from the new value.
            sample(3, 88.5, 990.0),
            sample(4, 88.0, 990.5),
            sample(5, 87.5, 991.0),
            sample(20, 87.5, 991.0)
        ))

        assertEquals(2, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:03:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:05:00Z", trips[0].endedAt)
        assertEquals(1.0, trips[1].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[1].startedAt)
        assertEquals("2026-11-26T10:02:00Z", trips[1].endedAt)
    }

    @Test fun smallOdometerRollbackAtMovementEpsilonDoesNotSplitTrip() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 89.5, 1000.5),
            sample(2, 89.0, 1000.48), // exactly the 0.02 km noise tolerance
            sample(3, 88.5, 1001.0),
            sample(18, 88.5, 1001.0)
        ))

        assertEquals(1, trips.size)
        assertEquals(1.0, trips[0].distanceKm, 0.001)
        assertEquals("2026-11-26T10:00:00Z", trips[0].startedAt)
        assertEquals("2026-11-26T10:03:00Z", trips[0].endedAt)
    }

    @Test fun isolatedOdometerSpikeDoesNotCreateFalseTrip() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(1, 90.0, 1012.0),
            sample(2, 90.0, 1000.0),
            sample(3, 90.0, 1000.2),
            sample(18, 90.0, 1000.2)
        ))

        assertTrue(trips.isEmpty())
    }

    @Test fun calculatesWeightedConsumptionFromTripsAtLeastOneKm() {
        val trips = listOf(
            TripSummary("2026-11-26T10:00:00Z", "2026-11-26T10:10:00Z", 10.0, 600, 5.0),
            TripSummary("2026-11-26T11:00:00Z", "2026-11-26T11:10:00Z", 20.0, 600, 8.0),
            TripSummary("2026-11-26T12:00:00Z", "2026-11-26T12:02:00Z", 0.6, 120, 2.0),
            TripSummary("2026-11-26T13:00:00Z", "2026-11-26T13:10:00Z", 5.0, 600, null)
        )
        val stats = TripConsumptionAnalyzer.summarize(trips)
        assertEquals(4, stats.tripCount)
        assertEquals(35.6, stats.totalDistanceKm, 0.001)
        assertEquals(2, stats.measuredTrips)
        assertEquals(13.0, stats.totalSocUsedPercent, 0.001)
        assertEquals(43.333, stats.averagePercentPer100Km!!, 0.01)
        assertEquals(66.75, stats.averageSpeedKmh!!, 0.001)
    }

    @Test fun rejectsConsumptionWhenSocEndpointsAreOutsideValidRange() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 105.0, 1000.0),
            sample(1, 103.0, 1000.5),
            sample(2, 100.0, 1001.0),
            sample(3, 100.0, 1001.0),
            sample(18, 100.0, 1001.0)
        ))
        assertEquals(1, trips.size)
        assertNull(trips[0].socUsedPercent)

        val stats = TripConsumptionAnalyzer.summarize(trips)
        assertEquals(1, stats.tripCount)
        assertEquals(0, stats.measuredTrips)
        assertNull(stats.averagePercentPer100Km)
    }

    @Test fun socIncreaseDuringTripIsNotReportedAsNegativeConsumption() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 50.0, 1000.0),
            sample(1, 50.5, 1000.5),
            sample(2, 51.0, 1001.0),
            sample(3, 51.0, 1001.0),
            sample(18, 51.0, 1001.0)
        ))
        assertEquals(1, trips.size)
        assertNull(trips[0].socUsedPercent)

        val stats = TripConsumptionAnalyzer.summarize(trips)
        assertEquals(1, stats.tripCount)
        assertEquals(0, stats.measuredTrips)
        assertNull(stats.averagePercentPer100Km)
    }

    @Test fun intermediateSocRiseInvalidatesConsumptionEvenWhenTripEndsLower() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 80.0, 1000.0),
            sample(1, 79.0, 1000.5),
            sample(2, 79.2, 1001.0), // meaningful +0.2 p.p. SOC rise
            sample(3, 78.0, 1001.5),
            sample(4, 75.0, 1002.0),
            sample(5, 75.0, 1002.0),
            sample(20, 75.0, 1002.0)
        ))
        assertEquals(1, trips.size)
        assertNull(trips[0].socUsedPercent)

        val stats = TripConsumptionAnalyzer.summarize(trips)
        assertEquals(1, stats.tripCount)
        assertEquals(0, stats.measuredTrips)
        assertNull(stats.averagePercentPer100Km)
    }

    @Test fun smallSocRiseBelowNoiseThresholdDoesNotInvalidateConsumption() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 80.0, 1000.0),
            sample(1, 79.0, 1000.5),
            sample(2, 79.19, 1001.0), // +0.19 p.p., below the 0.2 p.p. rejection threshold
            sample(3, 78.0, 1001.5),
            sample(4, 77.0, 1002.0),
            sample(5, 77.0, 1002.0),
            sample(20, 77.0, 1002.0)
        ))

        assertEquals(1, trips.size)
        assertEquals(3.0, trips[0].socUsedPercent!!, 0.001)
    }

    @Test fun invalidIntermediateSocInvalidatesConsumption() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 80.0, 1000.0),
            sample(1, 79.0, 1000.5),
            sample(2, Double.NaN, 1001.0),
            sample(3, 77.0, 1001.5),
            sample(4, 75.0, 1002.0),
            sample(5, 75.0, 1002.0),
            sample(20, 75.0, 1002.0)
        ))
        assertEquals(1, trips.size)
        assertNull(trips[0].socUsedPercent)
    }

    @Test fun returnsNoAverageWhenTripsHaveNoReliableSocMeasurement() {
        val trips = listOf(
            TripSummary("2026-11-26T10:00:00Z", "2026-11-26T10:10:00Z", 8.0, 600, null),
            TripSummary("2026-11-26T11:00:00Z", "2026-11-26T11:02:00Z", 0.7, 120, 1.0)
        )
        val stats = TripConsumptionAnalyzer.summarize(trips)
        assertEquals(2, stats.tripCount)
        assertEquals(0, stats.measuredTrips)
        assertNull(stats.averagePercentPer100Km)
    }


    @Test fun equalTimestampsCannotCreateACompletedTrip() {
        val trips = TripHistoryAnalyzer.analyze(listOf(
            sample(0, 90.0, 1000.0),
            sample(0, 89.5, 1000.6), // odometer changes without elapsed time
            sample(15, 89.5, 1000.6)
        ))

        assertTrue(trips.isEmpty())
    }

    @Test fun averageSpeedIsUnavailableWhenTripHasNoElapsedTime() {
        val trip = TripSummary("2026-11-26T10:00:00Z", "2026-11-26T10:00:00Z", 5.0, 0, 2.0)
        assertNull(trip.averageSpeedKmh)
        val stats = TripConsumptionAnalyzer.summarize(listOf(trip))
        assertNull(stats.averageSpeedKmh)
        assertEquals(0, stats.measuredTrips)
        assertNull(stats.averagePercentPer100Km)
    }
}
