package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RangeEstimatorTest {
    private fun snapshot(soc: Double, odo: Double, minute: Int) = TelemetrySnapshot(
        capturedAt = Instant.parse("2026-11-26T08:00:00Z").plusSeconds(minute * 60L).toString(),
        soc = soc, odometer = odo, stockRange = null,
        online = true, ready = true, sensorsJson = "{}"
    )

    @Test fun requiresEightCompleteFiveKilometerIntervalsBeforeEstimating() {
        val seven = (0..35 step 5).map { km -> snapshot(100.0 - km * 0.4, km.toDouble(), km) }
        assertNull(RangeEstimator.estimate(seven))
        assertNull(RangeEstimator.consumptionPer100Km(seven))

        val eight = seven + snapshot(84.0, 40.0, 40)
        assertEquals(210.0, RangeEstimator.estimate(eight)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(eight)!!, 0.001)
    }

    @Test fun interpolatesSocAtEachFiveKilometerBoundary() {
        val history = listOf(snapshot(100.0, 0.0, 0), snapshot(84.0, 40.0, 2))
        assertEquals(210.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun usesOnlyLatestEightValidFiveKilometerIntervals() {
        var soc = 100.0
        val history = mutableListOf(snapshot(soc, 0.0, 0))
        (1..9).forEach { interval ->
            soc -= interval.toDouble() * 0.2
            history.add(snapshot(soc, interval * 5.0, interval * 5))
        }
        // The first (0.2%) interval is discarded; average of 0.4..1.8 is 1.1% per 5 km.
        assertEquals(413.636, RangeEstimator.estimate(history)!!, 0.01)
        assertEquals(22.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun updatesRangeUsingLatestSocDuringPartialNinthInterval() {
        val complete = (0..8).map { interval ->
            snapshot(100.0 - interval * 2.5, interval * 5.0, interval * 5)
        }
        val partial = complete + snapshot(78.75, 42.5, 45)
        assertEquals(160.0, RangeEstimator.estimate(complete)!!, 0.001)
        assertEquals(157.5, RangeEstimator.estimate(partial)!!, 0.001)
    }

    @Test fun doesNotEstimateWhenSocDidNotDecrease() {
        val history = (0..8).map { interval ->
            snapshot(70.0 + interval * 0.1, interval * 5.0, interval * 5)
        }
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun preservesLastValidWindowAcrossTelemetryOutage() {
        val beforeOutage = (0..8).map { interval ->
            snapshot(100.0 - interval * 0.5, interval * 5.0, interval * 5)
        }
        val lastKnown = beforeOutage.last()
        val offlineSnapshot = snapshot(lastKnown.soc, lastKnown.odometer, 100)
        val history = beforeOutage + offlineSnapshot
        assertEquals(960.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(10.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun doesNotCountDistanceAcrossTelemetryOutageButKeepsPriorValidIntervals() {
        val history = (0..7).map { interval ->
            snapshot(100.0 - interval * 0.5, interval * 5.0, interval * 5)
        } + listOf(
            snapshot(96.5, 40.0, 100), // outage: do not infer the missing 5-km interval
            snapshot(96.0, 45.0, 105)
        )
        // Seven pre-outage intervals plus one fresh 5-km interval are available.
        assertEquals(960.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(10.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun resetsWindowAfterTrueOdometerRollback() {
        val history = listOf(
            snapshot(100.0, 50.0, 0),
            snapshot(99.5, 55.0, 5),
            snapshot(99.0, 0.0, 10)
        ) + (1..8).map { interval ->
            snapshot(99.0 - interval * 0.4, interval * 5.0, 10 + interval * 5)
        }
        assertEquals(1197.5, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(8.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun movingSocRiseResetsPartialIntervalAndRequiresEightNewIntervals() {
        val history = listOf(
            snapshot(100.0, 0.0, 0),
            snapshot(99.5, 5.0, 5),
            snapshot(100.0, 7.5, 7)
        ) + (1..8).map { interval ->
            snapshot(100.0 - interval * 0.4, 7.5 + interval * 5.0, 7 + interval * 5)
        }
        assertEquals(1210.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(8.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun stationaryChargingClearsPreChargeWindowAndRequiresEightPostChargeIntervals() {
        val beforeCharge = (0..8).map { interval ->
            snapshot(100.0 - interval * 0.5, interval * 5.0, interval * 5)
        }
        val afterCharge = listOf(snapshot(100.0, 40.0, 100)) +
            (1..8).map { interval ->
                snapshot(100.0 - interval * 0.4, 40.0 + interval * 5.0, 100 + interval * 5)
            }
        val history = beforeCharge + afterCharge
        assertEquals(1210.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(8.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }
}
