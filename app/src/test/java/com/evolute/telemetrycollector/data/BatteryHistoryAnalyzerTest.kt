package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryHistoryAnalyzerTest {
    private fun sample(at: String, json: String) = TelemetrySnapshot(at, 70.0, 100.0, null, true, true, json)

    @Test fun summarizesBatteryMetricFields() {
        val history = listOf(sample("2026-11-26T00:00:00Z", """{"batteryVoltage":350.0,"batteryTemperature":24,"odometer":100}"""), sample("2026-11-26T00:00:30Z", """{"batteryVoltage":351.0,"batteryTemperature":25,"odometer":101}"""))
        val report = BatteryHistoryAnalyzer.analyze(history)
        assertEquals(2, report.metrics.size)
        assertTrue(report.metrics.any { it.path == "batteryVoltage" && it.latest == 351.0 })
    }

    @Test fun flagsIsolatedSpikeThatReturnsToBaseline() {
        val history = listOf(sample("2026-11-26T00:00:00Z", """{"batteryVoltage":350}"""), sample("2026-11-26T00:00:30Z", """{"batteryVoltage":500}"""), sample("2026-11-26T00:01:00Z", """{"batteryVoltage":351}"""))
        assertTrue(BatteryHistoryAnalyzer.analyze(history).alerts.any { it.contains("одиночный скачок") })
    }

    @Test fun doesNotBridgeTelemetryGaps() {
        val history = listOf(sample("2026-11-26T00:00:00Z", """{"batteryVoltage":350}"""), sample("2026-11-26T00:10:00Z", """{"batteryVoltage":500}"""), sample("2026-11-26T00:10:30Z", """{"batteryVoltage":351}"""))
        assertTrue(BatteryHistoryAnalyzer.analyze(history).alerts.isEmpty())
    }
}