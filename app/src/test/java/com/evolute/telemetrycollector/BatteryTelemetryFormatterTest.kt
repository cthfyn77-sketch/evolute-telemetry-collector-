package com.evolute.telemetrycollector

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatteryTelemetryFormatterTest {
    @Test fun showsOnlyBatteryRelatedFieldsAndPreservesApiValues() {
        val raw = """{"sensors":{"batteryVoltage":352.4,"batteryCurrent":-12.5,"batteryTemperature":24,"odometer":1200}}"""
        val result = BatteryTelemetryFormatter.format(raw)

        assertTrue(result.contains("batteryVoltage: 352.4"))
        assertTrue(result.contains("batteryCurrent: -12.5"))
        assertTrue(result.contains("batteryTemperature: 24"))
        assertTrue(!result.contains("odometer"))
    }

    @Test fun handlesMissingBatteryMetricsWithoutInventingValues() {
        val result = BatteryTelemetryFormatter.format("""{"sensors":{"batteryPercentage":72,"odometer":1200}}""")
        assertTrue(result.contains("Дополнительные параметры батареи не переданы"))
    }

    @Test fun formatsBatteryCellArrays() {
        val result = BatteryTelemetryFormatter.format("""{"cellVoltages":[3.51,3.52,3.50]}""")
        assertTrue(result.contains("cellVoltages: 3.51, 3.52, 3.5"))
    }
    @Test fun ignoresSingleSohSpikeUntilThreeConsistentSamplesArrive() {
        fun sample(minute: Int, soh: Int) = com.evolute.telemetrycollector.data.TelemetrySnapshot(
            "2026-11-26T10:${minute.toString().padStart(2, '0')}:00Z",
            80.0, 1000.0, null, true, true, """{"battery":{"soh":$soh}}"""
        )
        val output = BatteryTelemetryFormatter.format(listOf(
            sample(0, 96), sample(1, 96), sample(2, 70), sample(3, 96)
        ))
        assertTrue(output.contains("soh: 96.0"))
        assertFalse(output.contains("soh: 70.0"))
    }

    @Test fun acceptsSohChangeAfterThreeConsistentReadings() {
        fun sample(minute: Int, soh: Int) = com.evolute.telemetrycollector.data.TelemetrySnapshot(
            "2026-11-26T10:${minute.toString().padStart(2, '0')}:00Z",
            80.0, 1000.0, null, true, true, """{"battery":{"soh":$soh}}"""
        )
        val output = BatteryTelemetryFormatter.format(listOf(
            sample(0, 96), sample(1, 96), sample(2, 90), sample(3, 90), sample(4, 90)
        ))
        assertTrue(output.contains("soh: 90.0"))
    }

}
