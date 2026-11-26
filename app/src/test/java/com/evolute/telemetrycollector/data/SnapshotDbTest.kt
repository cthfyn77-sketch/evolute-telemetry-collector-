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

    @Test fun exportsStoredRowsAsCsv() {
        db.insert(ApiClient.Telemetry(50.0, 1000.0, null, false, true, null))
        val output = ByteArrayOutputStream()
        db.exportCsv(output)
        val csv = output.toString(Charsets.UTF_8.name())

        assertTrue(csv.startsWith("captured_at,api_updated_at,soc,odometer,stock_range,online,ready"))
        assertTrue(csv.contains(Regex(",50(?:\\.0)?,1000(?:\\.0)?,,0,1")))
    }

    @Test fun csvEscapingPreservesCommasQuotesAndLineBreaks() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"sensor,value\"", csvField("sensor,value"))
        assertEquals("\"sensor \"\"value\"\"\"", csvField("sensor \"value\""))
        assertEquals("\"line one\r\nline two\"", csvField("line one\r\nline two"))
    }

    @Test fun exportsSensorJsonAsOneCsvField() {
        val json = """{"sensor":"value, with comma","note":"say \"hi\""}"""
        db.insert(ApiClient.Telemetry(50.0, 1000.0, null, false, true, null, json))
        val output = ByteArrayOutputStream()
        db.exportCsv(output)
        val csv = output.toString(Charsets.UTF_8.name())

        assertTrue(csv.contains(csvField(json)))
    }

    @Test fun recentSnapshotsHonorsAWindowLargerThanTwoHundredRows() {
        repeat(250) { index ->
            db.insert(ApiClient.Telemetry(80.0 - index * 0.01, 1000.0 + index, null, true, true, null))
        }
        assertEquals(240, db.recentSnapshots(240).size)
        assertEquals(250, db.recentSnapshots(1000).size)
    }

    @Test fun emptyDatabaseHasNoLatestRecord() {
        assertEquals(0L, db.count())
        assertEquals("Пока нет записей", db.latest())
    }

    @Test fun keepsServerUpdateTimeInAllSnapshotViews() {
        val serverTime = "2026-11-26T08:15:00Z"
        db.insert(ApiClient.Telemetry(64.0, 37165.0, 280.0, true, false, serverTime))

        assertEquals(serverTime, db.latestSnapshot()?.apiUpdatedAt)
        assertEquals(serverTime, db.recentSnapshots().single().apiUpdatedAt)
        assertEquals(serverTime, db.allSnapshots().single().apiUpdatedAt)
    }
}
