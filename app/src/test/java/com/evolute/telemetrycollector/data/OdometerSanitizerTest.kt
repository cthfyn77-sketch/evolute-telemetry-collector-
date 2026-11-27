package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OdometerSanitizerTest {
    private fun sample(second: Int, odo: Double, soc: Double = 80.0) =
        TelemetrySnapshot(
            java.time.Instant.parse("2026-11-26T10:00:00Z").plusSeconds(second.toLong()).toString(),
            soc, odo, null, true, true, "{}"
        )

    @Test fun removesOneShortLivedOdometerJump() {
        val history = listOf(
            sample(0, 1000.0),
            sample(30, 1012.0),
            sample(60, 1000.0),
            sample(90, 1000.5)
        )
        val clean = OdometerSanitizer.sanitize(history)
        assertEquals(listOf(1000.0, 1000.0, 1000.0, 1000.5), clean.map { it.odometer })
    }

    @Test fun keepsSustainedOdometerChange() {
        val history = listOf(sample(0, 1000.0), sample(30, 1012.0), sample(60, 1012.5))
        assertEquals(3, OdometerSanitizer.sanitize(history).size)
    }

    @Test fun doesNotFilterAcrossTelemetryGap() {
        val history = listOf(
            sample(0, 1000.0),
            TelemetrySnapshot("2026-11-26T10:10:00Z", 70.0, 1012.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T10:10:30Z", 70.0, 1000.0, null, true, true, "{}")
        )
        assertEquals(3, OdometerSanitizer.sanitize(history).size)
    }

    @Test fun removesShortLivedBackwardsOdometerDrop() {
        val history = listOf(
            sample(0, 1000.0),
            sample(30, 988.0),
            sample(60, 1000.0),
            sample(90, 1000.5)
        )
        val clean = OdometerSanitizer.sanitize(history)
        assertEquals(listOf(1000.0, 1000.0, 1000.0, 1000.5), clean.map { it.odometer })
    }

    @Test fun preservesOdometerCorrectionThatDoesNotReturnToBaseline() {
        val history = listOf(
            sample(0, 1000.0),
            sample(30, 1012.0),
            sample(60, 1011.0)
        )
        assertEquals(3, OdometerSanitizer.sanitize(history).size)
    }

    @Test fun sortsChronologicalSamplesBeforeCheckingSpike() {
        val history = listOf(
            sample(60, 1000.0),
            sample(0, 1000.0),
            sample(30, 1012.0)
        )
        val clean = OdometerSanitizer.sanitize(history)
        assertEquals(listOf(1000.0, 1000.0, 1000.0), clean.map { it.odometer })
        assertEquals(listOf(0L, 30L, 60L), clean.map {
            java.time.Instant.parse(it.capturedAt).epochSecond -
                java.time.Instant.parse("2026-11-26T10:00:00Z").epochSecond
        })
    }

    @Test fun keepsJumpWhenEitherNeighborGapExceedsThreeMinutes() {
        val history = listOf(
            sample(0, 1000.0),
            sample(30, 1012.0),
            sample(211, 1000.0)
        )
        assertEquals(3, OdometerSanitizer.sanitize(history).size)
    }
}
