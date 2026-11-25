package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test

class RangeEstimatorTest {
    private fun snapshot(soc: Double, odo: Double) = TelemetrySnapshot(
        capturedAt = "", soc = soc, odometer = odo, stockRange = null,
        online = true, ready = true, sensorsJson = "{}"
    )

    @Test fun estimatesAfterFiveKilometersUsingOneKilometerCheckpoints() {
        val history = (0..5).map { km -> snapshot(100.0 - km * 0.4, km.toDouble()) }
        // 2% SOC used over 5 km; 98% remains => 245 km.
        assertEquals(245.0, RangeEstimator.estimate(history)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(history)!!, 0.001)
    }

    @Test fun interpolatesSocAtEveryWholeKilometer() {
        val history = listOf(
            snapshot(100.0, 0.0),
            snapshot(98.0, 5.0)
        )
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

    @Test fun estimateUsesOnlyLastFiveKilometers() {
        val history = (0..20).map { km ->
            val soc = when {
                km <= 10 -> 100.0 - km * 2.0
                else -> 80.0 - (km - 10) * 0.2
            }
            snapshot(soc, km.toDouble())
        }
        // Last 5 km used 1% SOC; 79% remains => 395 km.
        assertEquals(395.0, RangeEstimator.estimate(history)!!, 0.001)
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
        val afterCharge = (1..10).map { km ->
            TelemetrySnapshot("2026-11-24T10:${(km * 2).toString().padStart(2, '0')}:00Z",
                90.0 - km * 0.4, 10.0 + km, null, true, true, "{}")
        }
        val combined = beforeCharge + charge + afterCharge
        // The estimator must use only the latest 5 km after charging: 2% consumed, 86% remains.
        assertEquals(215.0, RangeEstimator.estimate(combined)!!, 0.001)
        assertEquals(40.0, RangeEstimator.consumptionPer100Km(combined)!!, 0.001)
    }

    @Test fun estimateUpdatesAtEachNewKilometerCheckpoint() {
        val first = (0..5).map { km -> snapshot(100.0 - km * 0.5, km.toDouble()) }
        val next = first + snapshot(94.0, 6.0)
        assertEquals(195.0, RangeEstimator.estimate(first)!!, 0.001)
        assertEquals(85.4545455, RangeEstimator.estimate(next)!!, 0.001)
    }
}
