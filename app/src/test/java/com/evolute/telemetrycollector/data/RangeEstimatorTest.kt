package com.evolute.telemetrycollector.data

import org.junit.Assert.*
import org.junit.Test

class RangeEstimatorTest {
    private fun snapshot(soc: Double, odo: Double) = TelemetrySnapshot(
        capturedAt = "", soc = soc, odometer = odo, stockRange = null,
        online = true, ready = true, sensorsJson = "{}"
    )

    @Test fun waitsForTenKilometersAndUsesFiveKilometerCheckpoints() {
        val history = listOf(
            snapshot(100.0, 0.0),
            snapshot(98.0, 5.0),
            snapshot(96.0, 10.0)
        )
        // 4% SOC consumed over 10 km; 96% remaining => 240 km.
        assertEquals(240.0, RangeEstimator.estimate(history)!!, 0.001)
    }

    @Test fun interpolatesCheckpointsBetweenTelemetrySamples() {
        val history = listOf(
            snapshot(100.0, 0.0),
            snapshot(97.0, 7.5),
            snapshot(94.0, 15.0)
        )
        // The last completed 10-km window is 5..15 km: 4% SOC.
        assertEquals(235.0, RangeEstimator.estimate(history)!!, 0.001)
    }

    @Test fun doesNotEstimateWhenSocDidNotDecrease() {
        val history = listOf(
            snapshot(70.0, 0.0),
            snapshot(71.0, 5.0),
            snapshot(72.0, 10.0)
        )
        assertNull(RangeEstimator.estimate(history))
    }

    @Test fun estimateUsesOnlyLastTenKilometers() {
        val history = listOf(
            snapshot(100.0, 0.0),
            snapshot(90.0, 5.0),
            snapshot(80.0, 10.0),
            snapshot(79.0, 15.0),
            snapshot(78.0, 20.0)
        )
        // Last 10 km used 2% SOC; 78% remaining => 390 km.
        assertEquals(390.0, RangeEstimator.estimate(history)!!, 0.001)
    }
}
