package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryHistoryAnalyzerTest {
    private fun sample(at: String, json: String, odometer: Double = 100.0) = TelemetrySnapshot(at, 70.0, odometer, null, true, true, json)

    @Test fun summarizesBatteryMetricFields() {
        val history = listOf(sample("2026-11-26T00:00:00Z", """{"batteryVoltage":350.0,"batteryTemperature":24,"odometer":100}"""), sample("2026-11-26T00:00:30Z", """{"batteryVoltage":351.0,"batteryTemperature":25,"odometer":101}"""))
        val report = BatteryHistoryAnalyzer.analyze(history)
        assertEquals(3, report.metrics.size)
        assertTrue(report.metrics.any { it.path == "SOC" && it.latest == 70.0 })
        assertTrue(report.metrics.any { it.path == "batteryVoltage" && it.latest == 351.0 })
        assertEquals(101.0, report.metrics.single { it.path == "batteryVoltage" }.points.last().odometer!!, 0.001)
    }

    @Test fun flagsIsolatedSpikeThatReturnsToBaseline() {
        val history = listOf(sample("2026-11-26T00:00:00Z", """{"batteryVoltage":350}"""), sample("2026-11-26T00:00:30Z", """{"batteryVoltage":500}"""), sample("2026-11-26T00:01:00Z", """{"batteryVoltage":351}"""))
        val report = BatteryHistoryAnalyzer.analyze(history)
        assertTrue(report.alerts.any { it.contains("одиночный скачок") })
        val metric = report.metrics.single { it.path == "batteryVoltage" }
        assertEquals(2, metric.samples)
        assertEquals(351.0, metric.max, 0.001)
        assertTrue(metric.points.none { it.value == 500.0 })
    }

    @Test fun keepsSustainedLevelChangeInHistory() {
        val history = listOf(
            sample("2026-11-26T00:00:00Z", """{"batteryVoltage":350}"""),
            sample("2026-11-26T00:00:30Z", """{"batteryVoltage":420}"""),
            sample("2026-11-26T00:01:00Z", """{"batteryVoltage":421}""")
        )
        val metric = BatteryHistoryAnalyzer.analyze(history).metrics.single { it.path == "batteryVoltage" }
        assertEquals(3, metric.samples)
        assertEquals(421.0, metric.latest, 0.001)
    }

    @Test fun doesNotBridgeTelemetryGaps() {
        val history = listOf(sample("2026-11-26T00:00:00Z", """{"batteryVoltage":350}"""), sample("2026-11-26T00:10:00Z", """{"batteryVoltage":500}"""), sample("2026-11-26T00:10:30Z", """{"batteryVoltage":351}"""))
        assertTrue(BatteryHistoryAnalyzer.analyze(history).alerts.isEmpty())
    }
    @Test fun includesFirstClassSocInBatteryHistory() {
        val history = listOf(
            TelemetrySnapshot("2026-11-26T00:00:00Z", 82.0, 100.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T00:01:00Z", 79.0, 100.0, null, true, true, "{}"),
            TelemetrySnapshot("2026-11-26T00:02:00Z", 76.0, 100.0, null, true, true, "{}")
        )
        val soc = BatteryHistoryAnalyzer.analyze(history).metrics.single { it.path == "SOC" }
        assertEquals(3, soc.samples)
        assertEquals(76.0, soc.latest, 0.001)
        assertEquals(76.0, soc.points.last().value, 0.001)
    }

    @Test fun doesNotDuplicateSocWhenSensorJsonAlreadyContainsIt() {
        val history = listOf(
            sample("2026-11-26T00:00:00Z", """{"soc":80,"batteryVoltage":350}"""),
            sample("2026-11-26T00:00:30Z", """{"soc":79,"batteryVoltage":351}""")
        )
        val report = BatteryHistoryAnalyzer.analyze(history)
        assertEquals(1, report.metrics.count { it.path == "soc" || it.path == "SOC" })
        assertEquals(2, report.metrics.single { it.path == "soc" }.samples)
    }
}
