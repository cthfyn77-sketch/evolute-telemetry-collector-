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
}
