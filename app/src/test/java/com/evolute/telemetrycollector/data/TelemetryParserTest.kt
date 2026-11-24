package com.evolute.telemetrycollector.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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

    @Test fun parsesConfirmedAdditionalSensorsAndSignedTemperatures() {
        val result = TelemetryParser.parse(JSONObject("""{
            "sensors":{
                "batteryPercentage":"78","odometer":"37194","remainsBatteryMileage":"274",
                "batteryTemp":"4","12VBatteryVoltage":"12.68","coolantTemp":"70",
                "outsideTemp":"-1","inBoardTemp":"23","climateTargetTemp":"22",
                "climateFanSpeed":"0","isChargingGunInserted":false,"isParkedOn":true,
                "isCentralLockingOn":false,"isIgnitionOn":false,"trunkStatus":false,
                "signalLevel":"10","firmwareVersion":""
            },
            "isOnline":true,"isCarStateReady":true
        }"""))

        assertEquals(4.0, result.batteryTemp!!, 0.001)
        assertEquals(12.68, result.voltage12v!!, 0.001)
        assertEquals(70.0, result.coolantTemp!!, 0.001)
        assertEquals(-1.0, result.outsideTemp!!, 0.001)
        assertEquals(23.0, result.cabinTemp!!, 0.001)
        assertEquals(22.0, result.climateTargetTemp!!, 0.001)
        assertEquals(0.0, result.climateFanSpeed!!, 0.001)
        assertEquals(false, result.chargingGunInserted)
        assertEquals(true, result.parked)
        assertEquals(false, result.centralLockingOn)
        assertEquals(false, result.ignitionOn)
        assertEquals(false, result.trunkOpen)
        assertEquals(10.0, result.signalLevel!!, 0.001)
        assertNull(result.firmwareVersion)
    }

    @Test fun keepsUnknownOptionalSensorsNullAndAcceptsStringBooleans() {
        val result = TelemetryParser.parse(JSONObject("""{
            "sensors":{
                "batteryPercentage":50,"odometer":100,
                "isParkedOn":"true","isIgnitionOn":"false"
            }
        }"""))
        assertEquals(true, result.parked)
        assertEquals(false, result.ignitionOn)
        assertNull(result.batteryTemp)
        assertNull(result.voltage12v)
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
