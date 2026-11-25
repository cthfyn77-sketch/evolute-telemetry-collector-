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
        db.insert(ApiClient.Telemetry(64.0, 37165.0, 280.0, true, false, "api-time"))

        assertEquals(1L, db.count())
        val latest = db.latest()
        assertTrue(latest.contains("SOC: 64.0%"))
        assertTrue(latest.contains("Одометр: 37165.0 км"))
        assertTrue(latest.contains("Штатный запас: 280.0 км"))
        assertTrue(latest.contains("Онлайн: true; готовность: false"))
    }

    @Test fun smoothsPerKilometreConsumptionAndPublishesEstimateAtFiveKm() {
        db.insert(ApiClient.Telemetry(100.0, 0.4, 300.0, true, true, null))
        for (km in 1..4) {
            db.insert(ApiClient.Telemetry(100.0 - km, 0.4 + km, 300.0, true, true, null))
            // Stationary 30-second polls must not clear the kilometre history.
            db.insert(ApiClient.Telemetry(100.0 - km, 0.4 + km, 300.0, true, true, null))
        }

        assertTrue(db.latest().contains("Расчётный запас: накапливаем данные"))
        db.insert(ApiClient.Telemetry(95.0, 5.4, 280.0, true, true, null))

        val latest = db.latest()
        assertTrue(latest.contains("Расчётный запас: 95.0 км"))
        assertTrue(latest.contains("сглаженный расход 1.000%/км"))
        assertTrue(latest.contains("Штатный запас: 280.0 км"))

        // The smoothed consumption stays fixed between 5 km updates, but range
        // is recalculated from the latest SOC on every status refresh.
        db.insert(ApiClient.Telemetry(90.0, 5.4, 260.0, true, true, null))
        assertTrue(db.latest().contains("Расчётный запас: 90.0 км"))
    }

    @Test fun exportsStoredRowsAsCsv() {
        db.insert(ApiClient.Telemetry(50.0, 1000.0, null, false, true, null))
        val output = ByteArrayOutputStream()
        db.exportCsv(output)
        val csv = output.toString(Charsets.UTF_8.name())

        assertTrue(csv.startsWith("captured_at,api_updated_at,soc,odometer,stock_range,online,ready"))
        assertTrue(csv.contains(Regex(",50(?:\\.0)?,1000(?:\\.0)?,,0,1")))
    }

    @Test fun emptyDatabaseHasNoLatestRecord() {
        assertEquals(0L, db.count())
        assertEquals("Пока нет записей", db.latest())
    }
}
