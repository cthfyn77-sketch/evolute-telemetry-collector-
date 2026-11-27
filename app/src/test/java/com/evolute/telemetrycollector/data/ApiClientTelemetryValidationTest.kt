package com.evolute.telemetrycollector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiClientTelemetryValidationTest {
    private fun telemetry(
        soc: Double = 65.0,
        odometer: Double = 1234.5,
        stockRange: Double? = 210.0
    ) = ApiClient.Telemetry(
        soc = soc,
        odometer = odometer,
        stockRange = stockRange,
        online = true,
        ready = false,
        updatedAt = null,
        sensorsJson = "{}"
    )

    @Test fun acceptsValidTelemetry() {
        assertNull(ApiClient.validateTelemetry(telemetry()))
        assertNull(ApiClient.validateTelemetry(telemetry(soc = 0.0, odometer = 0.0, stockRange = 0.0)))
        assertNull(ApiClient.validateTelemetry(telemetry(soc = 100.0, stockRange = null)))
    }

    @Test fun rejectsSocOutsidePhysicalRangeOrNonFiniteValues() {
        assertEquals("Некорректный SOC", ApiClient.validateTelemetry(telemetry(soc = -0.1)))
        assertEquals("Некорректный SOC", ApiClient.validateTelemetry(telemetry(soc = 100.1)))
        assertEquals("Некорректный SOC", ApiClient.validateTelemetry(telemetry(soc = Double.NaN)))
        assertEquals("Некорректный SOC", ApiClient.validateTelemetry(telemetry(soc = Double.POSITIVE_INFINITY)))
    }

    @Test fun rejectsInvalidOdometer() {
        assertEquals("Некорректный одометр", ApiClient.validateTelemetry(telemetry(odometer = -1.0)))
        assertEquals("Некорректный одометр", ApiClient.validateTelemetry(telemetry(odometer = Double.NaN)))
        assertEquals("Некорректный одометр", ApiClient.validateTelemetry(telemetry(odometer = Double.POSITIVE_INFINITY)))
    }

    @Test fun rejectsNegativeOrNonFiniteStockRange() {
        assertEquals("Некорректный штатный запас хода", ApiClient.validateTelemetry(telemetry(stockRange = -1.0)))
        assertEquals("Некорректный штатный запас хода", ApiClient.validateTelemetry(telemetry(stockRange = Double.NaN)))
        assertEquals("Некорректный штатный запас хода", ApiClient.validateTelemetry(telemetry(stockRange = Double.POSITIVE_INFINITY)))
    }
}
