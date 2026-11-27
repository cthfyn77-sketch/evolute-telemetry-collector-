package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryFreshnessPolicyTest {
    @Test fun acceptsNewerServerTimestamp() {
        assertEquals(
            TelemetryFreshness.ACCEPT,
            TelemetryFreshnessPolicy.classify(
                "2026-11-27T10:01:00Z",
                "2026-11-27T10:00:00Z"
            )
        )
    }

    @Test fun rejectsExactDuplicateTimestamp() {
        assertEquals(
            TelemetryFreshness.DUPLICATE,
            TelemetryFreshnessPolicy.classify(
                "2026-11-27T10:00:00Z",
                "2026-11-27T10:00:00Z"
            )
        )
    }

    @Test fun rejectsOlderSnapshotArrivingLate() {
        assertEquals(
            TelemetryFreshness.STALE,
            TelemetryFreshnessPolicy.classify(
                "2026-11-27T09:59:00Z",
                "2026-11-27T10:00:00Z"
            )
        )
    }

    @Test fun comparesEquivalentInstantsWithDifferentOffsets() {
        assertEquals(
            TelemetryFreshness.DUPLICATE,
            TelemetryFreshnessPolicy.classify(
                "2026-11-27T11:00:00+01:00",
                "2026-11-27T10:00:00Z"
            )
        )
    }

    @Test fun acceptsWhenEitherServerTimestampIsMissingOrInvalid() {
        assertEquals(
            TelemetryFreshness.ACCEPT,
            TelemetryFreshnessPolicy.classify(null, "2026-11-27T10:00:00Z")
        )
        assertEquals(
            TelemetryFreshness.ACCEPT,
            TelemetryFreshnessPolicy.classify("not-a-date", "2026-11-27T10:00:00Z")
        )
        assertEquals(
            TelemetryFreshness.ACCEPT,
            TelemetryFreshnessPolicy.classify("2026-11-27T10:01:00Z", null)
        )
    }
}
