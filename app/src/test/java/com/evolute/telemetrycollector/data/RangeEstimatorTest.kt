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

    @Test fun estimatesAfterOneCompleteFiveKilometerInterval() {
        val history = (0..5).map { km -> snapshot(100.0 - km * 0.4, km.toDouble()) }
        // 2% SOC used over 5 km; 98% remains => 245 km.
        assertEquals(245.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun interpolatesSocAtFiveKilometerBoundary() {
        val history = listOf(snapshot(100.0, 0.0), snapshot(98.0, 5.0))
        assertEquals(245.0, RangeEstimator.estimate(history)!!, 0.001)
    }

    @Test fun doesNotEstimateBeforeFiveKilometers() {
        val history = (0..4).map { km -> snapshot(100.0 - km * 0.5, km.toDouble()) }
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun doesNotEstimateWhenSocDidNotDecrease() {
        val history = (0..5).map { km -> snapshot(70.0 + km * 0.1, km.toDouble()) }
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun usesOnlyLatestEightValidFiveKilometerIntervals() {
        val intervalConsumptions = (1..9).map { it.toDouble() }
        var soc = 100.0
        val history = mutableListOf(snapshot(soc, 0.0, 0))
        intervalConsumptions.forEachIndexed { index, consumed ->
            soc -= consumed
            val km = (index + 1) * 5.0
            history.add(snapshot(soc, km, (index + 1) * 2))
        }
        // The first (1%) interval is discarded. Average of 2..9 is 5.5% per 5 km,
        // i.e. 1.1%/km; 55% remaining gives a 50 km estimate.
        assertEquals(50.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(110.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun estimateUsesAverageOfAvailableIntervalsAndLatestSoc() {
        val history = (0..10).map { km ->
            val soc = when {
                km <= 5 -> 100.0 - km * 0.4
                else -> 98.0 - (km - 5) * 0.2
            }
            snapshot(soc, km.toDouble())
        }
        // Two intervals: 2% and 1% per 5 km; average is 0.3%/km.
        // 97% remains => 323.333... km.
        assertEquals(323.333333, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(30.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun chargingSessionResetsFiveKilometerConsumptionWindow() {
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

    @Test fun estimateUsesLatestSocBeforeNextFiveKilometerBoundary() {
        val first = (0..5).map { km -> snapshot(100.0 - km * 0.5, km.toDouble()) }
        val next = first + snapshot(94.0, 6.0, 7)
        assertEquals(195.0, RangeEstimator.estimate(first)!!, 0.001)
        assertEquals(188.0, RangeEstimator.estimate(next)!!, 0.001)
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
}
