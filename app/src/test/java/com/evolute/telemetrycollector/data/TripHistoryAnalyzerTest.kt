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
        assertEquals(30.0, trips[0].averageSpeedKmh!!, 0.001)
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
            sample(3, 90.0, 1000.5),
            sample(18, 90.0, 1000.5)
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


    @Test fun averageSpeedIsUnavailableWhenTripHasNoElapsedTime() {
        val trip = TripSummary("2026-11-26T10:00:00Z", "2026-11-26T10:00:00Z", 5.0, 0, 2.0)
        assertNull(trip.averageSpeedKmh)
        assertNull(TripConsumptionAnalyzer.summarize(listOf(trip)).averageSpeedKmh)
    }
}
