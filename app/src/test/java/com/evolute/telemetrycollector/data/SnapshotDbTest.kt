package com.evolute.telemetrycollector.data

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SnapshotDbTest {
    private lateinit var context: Context
    private lateinit var db: SnapshotDb

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("evolute_collector.sqlite")
        db = SnapshotDb.get(context)
    }

    @After fun tearDown() {
        SnapshotDb.resetForTests()
        context.deleteDatabase("evolute_collector.sqlite")
    }

    @Test fun storesSnapshotAndReturnsLatestRecord() {
        db.insert(ApiClient.Telemetry(
            64.0, 37165.0, 280.0, true, false, "api-time",
            batteryTemp = 4.0,
            voltage12v = 12.68,
            outsideTemp = -1.0,
            cabinTemp = 23.0,
            chargingGunInserted = false,
            parked = true
        ))

        assertEquals(1L, db.count())
        val latest = db.latest()
        assertTrue(latest.contains("SOC: 64.0%"))
        assertTrue(latest.contains("Одометр: 37165.0 км"))
        assertTrue(latest.contains("Штатный запас: 280.0 км"))
        assertTrue(latest.contains("Онлайн: true; готовность: false"))
        assertTrue(latest.contains("Температура батареи: 4.0 °C"))
        assertTrue(latest.contains("Напряжение 12 В: 12.68 В"))
        assertTrue(latest.contains("Температура снаружи: -1.0 °C"))
        assertTrue(latest.contains("Температура салона: 23.0 °C"))
        assertTrue(latest.contains("Зарядный разъём подключён: false"))
        assertTrue(latest.contains("Автомобиль припаркован: true"))
    }

    @Test fun exportsStoredRowsAsCsv() {
        db.insert(ApiClient.Telemetry(
            50.0, 1000.0, null, false, true, null,
            rawSensorsJson = """{"instantPower":18.6,"motorCurrent":42.1}"""
        ))
        val output = ByteArrayOutputStream()
        db.exportCsv(output)
        val csv = output.toString(Charsets.UTF_8.name())

        assertTrue(csv.startsWith("captured_at,api_updated_at,soc,odometer,stock_range,online,ready,battery_temp"))
        assertTrue(csv.contains("raw_sensors_json"))
        assertTrue(csv.contains("instantPower"))
        assertTrue(csv.contains("18.6"))
        assertTrue(csv.contains(Regex(",50(?:\\.0)?,1000(?:\\.0)?,,0,1,")))
    }

    @Test fun emptyDatabaseHasNoLatestRecord() {
        assertEquals(0L, db.count())
        assertEquals("Пока нет записей", db.latest())
    }
}
