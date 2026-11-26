package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RangeEstimatorTest {
    private fun snapshot(soc: Double, odo: Double, minute: Int = odo.toInt()) = TelemetrySnapshot(
        capturedAt = Instant.parse("2026-11-26T08:00:00Z").plusSeconds(minute * 60L).toString(),
        soc = soc, odometer = odo, stockRange = null,
        online = true, ready = true, sensorsJson = "{}"
    )

    @Test fun estimatesFromSocConsumptionRecordedEveryKilometer() {
        val history = (0..5).map { km -> snapshot(100.0 - km * 0.4, km.toDouble()) }
        // 0.4% SOC used per km; 98% remains => 245 km.
        assertEquals(245.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun interpolatesSocAtEachOneKilometerBoundary() {
        val history = listOf(snapshot(100.0, 0.0, 0), snapshot(98.0, 5.0, 2))
        assertEquals(245.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun doesNotEstimateBeforeFirstCompleteKilometer() {
        assertNull(RangeEstimator.estimate(listOf(snapshot(100.0, 0.0))))
    }

    @Test fun doesNotEstimateWhenSocDidNotDecrease() {
        val history = (0..5).map { km -> snapshot(70.0 + km * 0.1, km.toDouble()) }
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun usesOnlyLatestEightValidOneKilometerIntervals() {
        val intervalConsumptions = (1..9).map { it.toDouble() }
        var soc = 100.0
        val history = mutableListOf(snapshot(soc, 0.0, 0))
        intervalConsumptions.forEachIndexed { index, consumed ->
            soc -= consumed
            val km = (index + 1).toDouble()
            history.add(snapshot(soc, km, (index + 1) * 2))
        }
        // The first (1%) interval is discarded. Average of 2..9 is 5.5% per km.
        assertEquals(10.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(550.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun estimateUsesAverageOfAvailableIntervalsAndLatestSoc() {
        val history = (0..10).map { km ->
            val soc = when {
                km <= 5 -> 100.0 - km * 0.4
                else -> 98.0 - (km - 5) * 0.2
            }
            snapshot(soc, km.toDouble())
        }
        // The rolling window keeps the latest 8 intervals: three at 0.4% and five at 0.2%.
        // Average is 0.275%/km; 97% remains => about 352.727 km.
        assertEquals(352.727273, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(27.5, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun chargingSessionResetsOneKilometerConsumptionWindow() {
        val beforeCharge = (0..10).map { km ->
            TelemetrySnapshot("2026-11-24T08:${(km * 2).toString().padStart(2, '0')}:00Z",
                80.0 - km * 0.5, km.toDouble(), null, true, true, "{}")
        }
        val charge = listOf(
            TelemetrySnapshot("2026-11-24T09:00:00Z", 80.0, 10.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-24T09:30:00Z", 90.0, 10.0, null, true, true, "{}")
        )
        val afterCharge = listOf(
            TelemetrySnapshot("2026-11-24T10:00:00Z", 90.0, 10.0, null, true, true, "{}")
        ) + (1..10).map { km ->
            TelemetrySnapshot("2026-11-24T10:${(km * 2).toString().padStart(2, '0')}:00Z",
                90.0 - km * 0.4, 10.0 + km, null, true, true, "{}")
        }
        val combined = beforeCharge + charge + afterCharge
        assertEquals(215.0, RangeEstimator.estimate(combined)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(combined)!!, 0.001)
    }

    @Test fun estimateUpdatesWithLatestSocBeforeNextKilometerBoundary() {
        val first = (0..5).map { km -> snapshot(100.0 - km * 0.5, km.toDouble()) }
        val partial = first + snapshot(97.25, 5.5, 7)
        assertEquals(195.0, RangeEstimator.estimate(first)!!, 0.001)
        assertEquals(194.5, RangeEstimator.estimate(partial)!!, 0.001)
    }

    @Test fun resetsConsumptionWindowAfterStationaryTelemetryGap() {
        val history = listOf(
            TelemetrySnapshot("2026-11-26T08:00:00Z", 100.0, 0.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:02:00Z", 99.5, 1.0, null, true, true, "{}"),
            // The car is stationary during the outage, but SOC changes substantially.
            TelemetrySnapshot("2026-11-26T08:20:00Z", 80.0, 1.0, null, true, true, "{}")
        ) + (1..5).map { km ->
            TelemetrySnapshot(Instant.parse("2026-11-26T08:20:00Z").plusSeconds(km * 60L).toString(),
                80.0 - km * 0.4, 1.0 + km, null, true, true, "{}")
        }

        // Only the five post-outage intervals (0.4%/km) may influence the estimate.
        assertEquals(195.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun resetsConsumptionWindowAfterLongTelemetryGap() {
        val history = listOf(
            TelemetrySnapshot("2026-11-26T08:00:00Z", 100.0, 0.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:10:00Z", 80.0, 5.0, null, true, true, "{}")
        ) + (1..5).map { km ->
            TelemetrySnapshot(Instant.parse("2026-11-26T08:10:00Z").plusSeconds(km * 60L).toString(),
                80.0 - km * 0.4, 5.0 + km, null, true, true, "{}")
        }
        assertEquals(195.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }


    @Test fun doesNotReusePreOutageIntervalsBeforeFirstPostOutageKilometer() {
        val history = listOf(
            TelemetrySnapshot("2026-11-26T08:00:00Z", 100.0, 0.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:01:00Z", 99.5, 1.0, null, true, true, "{}"),
            // Recovery after a long outage establishes a new baseline.
            TelemetrySnapshot("2026-11-26T08:20:00Z", 80.0, 1.0, null, true, true, "{}"),
            // Not enough post-recovery distance to complete a new 1-km interval.
            TelemetrySnapshot("2026-11-26T08:25:00Z", 79.8, 1.5, null, true, true, "{}")
        )

        // The pre-outage 0.5%/km interval must not be reused after recovery.
        assertNull(RangeEstimator.estimate(history))
        assertNull(RangeEstimator.consumptionPer100Km(history))
    }


    @Test fun keepsRangeWindowWhenTelemetryGapIsExactlyThreeMinutes() {
        val history = listOf(
            TelemetrySnapshot("2026-11-26T08:00:00Z", 100.0, 0.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:01:00Z", 99.5, 1.0, null, true, true, "{}"),
            // Exactly 180 seconds is not a gap; the rule resets only above 180 seconds.
            TelemetrySnapshot("2026-11-26T08:04:00Z", 99.5, 1.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:05:00Z", 99.1, 2.0, null, true, true, "{}")
        )

        // Both valid intervals remain in the rolling window: average 0.45%/km.
        assertEquals(220.222222, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(45.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }


    @Test fun invalidatesRisingSocIntervalWithoutBridgingFollowingIntervals() {
        val history = listOf(
            TelemetrySnapshot("2026-11-26T08:00:00Z", 80.0, 0.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:01:00Z", 79.5, 1.0, null, true, true, "{}"),
            // SOC rises while moving: this kilometre is invalid and must not
            // be averaged or combined with the following kilometre.
            TelemetrySnapshot("2026-11-26T08:02:00Z", 80.5, 2.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:03:00Z", 80.1, 3.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T08:04:00Z", 79.7, 4.0, null, true, true, "{}")
        )

        // The first 0.5% interval remains valid; the rising interval is discarded,
        // and the next two intervals are independently measured at 0.4%/km.
        // Average: (0.5 + 0.4 + 0.4) / 3 = 0.433333...%/km.
        assertEquals(183.923077, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(43.333333, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }


    @Test fun sortsOutOfOrderSnapshotsBeforeEstimatingRange() {
        val chronological = (0..5).map { km ->
            snapshot(80.0 - km * 0.4, km.toDouble(), km)
        }
        // Database retrieval order can differ from capture time; calculation
        // must use timestamp order when every timestamp is valid.
        val shuffled = listOf(
            chronological[3], chronological[0], chronological[5],
            chronological[2], chronological[1], chronological[4]
        )

        assertEquals(195.0, RangeEstimator.estimate(shuffled)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(shuffled)!!, 0.001)
    }


    @Test fun ignoresOdometerReversalWithoutBridgingOrDoubleCountingIntervals() {
        val history = listOf(
            snapshot(80.0, 0.0, 0),
            snapshot(79.5, 1.0, 1),
            // A reversed odometer sample must not create a negative-distance interval.
            snapshot(78.0, 0.5, 2),
            snapshot(79.1, 2.0, 3)
        )

        // The 0–1 km interval consumes 0.5%; the reversal is ignored;
        // the next completed checkpoint consumes 0.4%. Average = 0.45%/km.
        assertEquals(175.777778, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(45.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

}
