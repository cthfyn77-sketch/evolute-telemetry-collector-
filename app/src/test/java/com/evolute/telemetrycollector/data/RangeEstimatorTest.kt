package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RangeEstimatorTest {
    private fun snapshot(soc: Double, odo: Double, minute: Int, sensorsJson: String = "{}") = TelemetrySnapshot(
        capturedAt = Instant.parse("2026-11-26T08:00:00Z").plusSeconds(minute * 60L).toString(),
        soc = soc, odometer = odo, stockRange = null,
        online = true, ready = true, sensorsJson = sensorsJson
    )

    @Test fun requiresEightCompleteValidIntervalsBeforeEstimating() {
        val seven = (0..7).map { km -> snapshot(100.0 - km * 0.4, km.toDouble(), km) }
        assertNull(RangeEstimator.estimate(seven))
        assertNull(RangeEstimator.consumptionPer100Km(seven))

        val eight = seven + snapshot(96.8, 8.0, 8)
        assertEquals(242.0, RangeEstimator.estimate(eight)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(eight)!!, 0.001)
    }

    @Test fun interpolatesSocAtEachOneKilometerBoundary() {
        val history = listOf(snapshot(100.0, 0.0, 0), snapshot(96.8, 8.0, 2))
        assertEquals(242.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun usesOnlyLatestEightValidOneKilometerIntervals() {
        var soc = 100.0
        val history = mutableListOf(snapshot(soc, 0.0, 0))
        (1..9).forEach { km ->
            soc -= km.toDouble()
            history.add(snapshot(soc, km.toDouble(), km))
        }
        // The first (1%) interval is discarded; average of 2..9 is 5.5%/km.
        assertEquals(10.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(550.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun updatesRangeUsingLatestSocDuringPartialNinthInterval() {
        val complete = (0..8).map { km -> snapshot(100.0 - km * 0.5, km.toDouble(), km) }
        val partial = complete + snapshot(95.75, 8.5, 9)
        assertEquals(192.0, RangeEstimator.estimate(complete)!!, 0.001)
        assertEquals(191.5, RangeEstimator.estimate(partial)!!, 0.001)
    }

    @Test fun doesNotEstimateWhenSocDidNotDecrease() {
        val history = (0..8).map { km -> snapshot(70.0 + km * 0.1, km.toDouble(), km) }
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun resetsWindowAfterTelemetryGapAndRequiresEightNewIntervals() {
        val history = listOf(
            snapshot(100.0, 0.0, 0),
            snapshot(99.5, 1.0, 1),
            snapshot(80.0, 1.0, 20)
        ) + (1..8).map { km ->
            snapshot(80.0 - km * 0.4, 1.0 + km, 20 + km)
        }
        assertEquals(192.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun doesNotReusePreOutageIntervalsBeforeEightNewIntervals() {
        val history = listOf(
            snapshot(100.0, 0.0, 0),
            snapshot(99.5, 1.0, 1),
            snapshot(80.0, 1.0, 20),
            snapshot(79.8, 1.5, 25)
        )
        assertNull(RangeEstimator.estimate(history))
        assertNull(RangeEstimator.consumptionPer100Km(history))
    }

    @Test fun keepsWindowWhenTelemetryGapIsExactlyThreeMinutes() {
        val history = (0..4).map { km -> snapshot(100.0 - km * 0.5, km.toDouble(), km) } +
            listOf(snapshot(98.0, 4.0, 7)) +
            (5..8).map { km -> snapshot(100.0 - km * 0.5, km.toDouble(), km + 3) }
        // Eight valid intervals remain in the window; average consumption is 0.5%/km.
        assertEquals(192.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(50.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun resetsWindowAfterTrueOdometerRollback() {
        val history = listOf(
            snapshot(100.0, 10.0, 0),
            snapshot(99.5, 11.0, 1),
            snapshot(99.0, 0.0, 2)
        ) + (1..8).map { km -> snapshot(99.0 - km * 0.4, km.toDouble(), 2 + km) }
        assertEquals(239.5, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun movingSocRiseResetsPartialIntervalAndRequiresEightNewIntervals() {
        val history = listOf(
            snapshot(100.0, 0.0, 0),
            snapshot(99.5, 1.0, 1),
            snapshot(100.0, 1.5, 2)
        ) + (1..8).map { km ->
            snapshot(100.0 - km * 0.4, 1.5 + km, 2 + km)
        }
        assertEquals(242.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun stationaryChargingClearsPreChargeWindowAndRequiresEightPostChargeIntervals() {
        val beforeCharge = (0..8).map { km -> snapshot(100.0 - km * 0.5, km.toDouble(), km) }
        val afterCharge = listOf(snapshot(100.0, 8.0, 20)) +
            (1..8).map { km -> snapshot(100.0 - km * 0.4, 8.0 + km, 20 + km) }
        val history = beforeCharge + afterCharge
        assertEquals(242.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun preservesLastValidWindowDuringTelemetryOutage() {
        val beforeOutage = (0..8).map { km ->
            snapshot(100.0 - km * 0.5, km.toDouble(), km)
        }
        val offline = snapshot(96.0, 20.0, 20)
        val history = beforeOutage + offline
        assertEquals(192.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(50.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun doesNotCountMissingDistanceAfterTelemetryOutage() {
        val beforeOutage = (0..8).map { km ->
            snapshot(100.0 - km * 0.5, km.toDouble(), km)
        }
        val restored = snapshot(96.0, 20.0, 20)
        val history = beforeOutage + restored + (1..8).map { km ->
            snapshot(96.0 - km * 0.4, 20.0 + km, 20 + km)
        }
        assertEquals(232.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }
}
