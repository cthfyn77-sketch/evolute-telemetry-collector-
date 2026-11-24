package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test

class RangeEstimatorTest {
    private fun snapshot(soc: Double, odo: Double) = TelemetrySnapshot(
        capturedAt = "", soc = soc, odometer = odo, stockRange = null,
        online = true, ready = true, sensorsJson = "{}"
    )

    @Test fun estimatesAfterTenKilometersUsingOneKilometerCheckpoints() {
        val history = (0..10).map { km -> snapshot(100.0 - km * 0.4, km.toDouble()) }
        // 4% SOC used over 10 km; 96% remains => 240 km.
        assertEquals(240.0, RangeEstimator.estimate(history)!!, 0.001)
    }

    @Test fun interpolatesSocAtEveryWholeKilometer() {
        val history = listOf(
            snapshot(100.0, 0.0),
            snapshot(96.0, 10.0)
        )
        assertEquals(240.0, RangeEstimator.estimate(history)!!, 0.001)
    }

    @Test fun doesNotEstimateBeforeTenKilometers() {
        val history = (0..9).map { km -> snapshot(100.0 - km * 0.5, km.toDouble()) }
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun doesNotEstimateWhenSocDidNotDecrease() {
        val history = (0..10).map { km -> snapshot(70.0 + km * 0.1, km.toDouble()) }
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun estimateUsesOnlyLastTenKilometers() {
        val history = (0..20).map { km ->
            val soc = when {
                km <= 10 -> 100.0 - km * 2.0
                else -> 80.0 - (km - 10) * 0.2
            }
            snapshot(soc, km.toDouble())
        }
        // Last 10 km used 2% SOC; 78% remains => 390 km.
        assertEquals(390.0, RangeEstimator.estimate(history)!!, 0.001)
    }

    @Test fun estimateUpdatesAtEachNewKilometerCheckpoint() {
        val first = (0..10).map { km -> snapshot(100.0 - km * 0.5, km.toDouble()) }
        val next = first + snapshot(94.0, 11.0)
        assertEquals(190.0, RangeEstimator.estimate(first)!!, 0.001)
        assertEquals(188.0, RangeEstimator.estimate(next)!!, 0.001)
    }
}
