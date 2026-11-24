package com.evolute.telemetrycollector.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TelemetryParserTest {
    @Test fun parsesValidTelemetry() {
        val result = TelemetryParser.parse(JSONObject("""{
            "sensors":{"batteryPercentage":76.5,"odometer":37165.2,"remainsBatteryMileage":284},
            "isOnline":true,"isCarStateReady":true,"carStateUpdatedAt":"2026-11-24T12:00:00Z"
        }"""))

        assertEquals(76.5, result.soc, 0.001)
        assertEquals(37165.2, result.odometer, 0.001)
        assertEquals(284.0, result.stockRange!!, 0.001)
        assertTrue(result.online)
        assertTrue(result.ready)
        assertEquals("2026-11-24T12:00:00Z", result.updatedAt)
    }

    @Test fun rejectsSocAboveOneHundred() {
        assertThrows(IOException::class.java) {
            TelemetryParser.parse(JSONObject("""{"sensors":{"batteryPercentage":101,"odometer":100}}"""))
        }
    }

    @Test fun rejectsNegativeOrMissingSoc() {
        assertThrows(IOException::class.java) {
            TelemetryParser.parse(JSONObject("""{"sensors":{"batteryPercentage":-1,"odometer":100}}"""))
        }
        assertThrows(IOException::class.java) {
            TelemetryParser.parse(JSONObject("""{"sensors":{"odometer":100}}"""))
        }
    }

    @Test fun rejectsMissingSensorsAndOdometer() {
        assertThrows(IOException::class.java) { TelemetryParser.parse(JSONObject("{}")) }
        assertThrows(IOException::class.java) {
            TelemetryParser.parse(JSONObject("""{"sensors":{"batteryPercentage":50}}"""))
        }
    }

    @Test fun ignoresInvalidOptionalRange() {
        val result = TelemetryParser.parse(JSONObject("""{
            "sensors":{"batteryPercentage":50,"odometer":100,"remainsBatteryMileage":"unknown"}
        }"""))
        assertNull(result.stockRange)
    }
}
