package com.evolute.telemetrycollector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Escapes one CSV field according to RFC 4180.
 * Fields containing delimiters, quotes or line breaks are quoted; embedded quotes are doubled.
 */
internal fun csvField(value: String): String {
    if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

data class TelemetrySnapshot(
    val capturedAt: String,
    val soc: Double,
    val odometer: Double,
    val stockRange: Double?,
    val online: Boolean,
    val ready: Boolean,
    val sensorsJson: String,
    val apiUpdatedAt: String? = null
)

class SnapshotDb private constructor(c: Context) : SQLiteOpenHelper(c, "evolute_collector.sqlite", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE snapshots(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            captured_at TEXT NOT NULL,
            api_updated_at TEXT,
            soc REAL NOT NULL,
            odometer REAL NOT NULL,
            stock_range REAL,
            online INTEGER NOT NULL,
            ready INTEGER NOT NULL,
            sensors_json TEXT NOT NULL DEFAULT '{}'
        )""")
        db.execSQL("CREATE INDEX idx_snapshots_captured ON snapshots(captured_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE snapshots ADD COLUMN sensors_json TEXT NOT NULL DEFAULT '{}'")
    }

    /**
     * Inserts only snapshots newer than the latest server-timestamped record.
     * The transaction makes comparison + insert atomic if polling is ever invoked concurrently.
     */
    internal fun insert(t: ApiClient.Telemetry): TelemetryFreshness {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val latestUpdatedAt = db.rawQuery(
                "SELECT api_updated_at FROM snapshots WHERE api_updated_at IS NOT NULL ORDER BY id DESC",
                null
            ).use { c ->
                var latest: String? = null
                while (c.moveToNext() && latest == null) {
                    val candidate = c.getString(0)
                    if (TelemetryFreshnessPolicy.isValidTimestamp(candidate)) latest = candidate
                }
                latest
            }
            val freshness = TelemetryFreshnessPolicy.classify(t.updatedAt, latestUpdatedAt)
            if (freshness != TelemetryFreshness.ACCEPT) return freshness

            val v = ContentValues().apply {
                put("captured_at", java.time.Instant.now().toString())
                put("api_updated_at", t.updatedAt)
                put("soc", t.soc); put("odometer", t.odometer); put("stock_range", t.stockRange)
                put("online", if (t.online) 1 else 0); put("ready", if (t.ready) 1 else 0)
                put("sensors_json", t.sensorsJson)
            }
            db.insertOrThrow("snapshots", null, v)
            db.setTransactionSuccessful()
            return TelemetryFreshness.ACCEPT
        } finally {
            db.endTransaction()
        }
    }

    /** Estimated range from complete 1-km SOC intervals, averaging the latest eight valid intervals. */
    private fun estimationHistory(): List<TelemetrySnapshot> = readableDatabase.rawQuery(
            "SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json FROM snapshots ORDER BY id ASC",
            null
        ).use { c ->
            val rows = mutableListOf<TelemetrySnapshot>()
            while (c.moveToNext()) rows.add(TelemetrySnapshot(
                c.getString(0), c.getDouble(2), c.getDouble(3),
                if (c.isNull(4)) null else c.getDouble(4), c.getInt(5) == 1,
                c.getInt(6) == 1, c.getString(7), if (c.isNull(1)) null else c.getString(1)
            ))
            rows
        }
    fun calculatedRangeKm(): Double? = RangeEstimator.estimate(estimationHistory())

    fun averageConsumptionPer100Km(): Double? = RangeEstimator.consumptionPer100Km(estimationHistory())

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    fun latestSnapshot(): TelemetrySnapshot? = readableDatabase.rawQuery(
        "SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json FROM snapshots ORDER BY id DESC LIMIT 1", null
    ).use { c ->
        if (!c.moveToFirst()) return null
        TelemetrySnapshot(c.getString(0), c.getDouble(2), c.getDouble(3),
            if (c.isNull(4)) null else c.getDouble(4), c.getInt(5) == 1, c.getInt(6) == 1,
            c.getString(7), if (c.isNull(1)) null else c.getString(1))
    }

    fun recentSnapshots(limit: Int = 24): List<TelemetrySnapshot> =
        readableDatabase.rawQuery(
            "SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json FROM snapshots ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 1000).toString())
        ).use { c ->
            val rows = mutableListOf<TelemetrySnapshot>()
            while (c.moveToNext()) rows.add(TelemetrySnapshot(
                c.getString(0), c.getDouble(2), c.getDouble(3),
                if (c.isNull(4)) null else c.getDouble(4), c.getInt(5) == 1, c.getInt(6) == 1,
                c.getString(7), if (c.isNull(1)) null else c.getString(1)
            ))
            rows.reverse()
            rows
        }

    /**
     * Battery sensor history for a selectable time window.
     * A null cutoff returns every locally retained snapshot; no rows are deleted.
     */
    fun batteryHistorySnapshots(capturedAfter: String? = null): List<TelemetrySnapshot> {
        val sql = if (capturedAfter == null) {
            "SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json FROM snapshots ORDER BY id ASC"
        } else {
            "SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json FROM snapshots WHERE julianday(captured_at) >= julianday(?) ORDER BY id ASC"
        }
        val args = if (capturedAfter == null) null else arrayOf(capturedAfter)
        return readableDatabase.rawQuery(sql, args).use { c ->
            val rows = mutableListOf<TelemetrySnapshot>()
            while (c.moveToNext()) rows.add(TelemetrySnapshot(
                c.getString(0), c.getDouble(2), c.getDouble(3),
                if (c.isNull(4)) null else c.getDouble(4), c.getInt(5) == 1,
                c.getInt(6) == 1, c.getString(7), if (c.isNull(1)) null else c.getString(1)
            ))
            rows
        }
    }
    /** Full chronological sample window for local trip reconstruction. */
    fun allSnapshots(limit: Int = 20000): List<TelemetrySnapshot> =
        readableDatabase.rawQuery(
            "SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json FROM snapshots ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 50000).toString())
        ).use { c ->
            val rows = mutableListOf<TelemetrySnapshot>()
            while (c.moveToNext()) rows.add(TelemetrySnapshot(
                c.getString(0), c.getDouble(2), c.getDouble(3),
                if (c.isNull(4)) null else c.getDouble(4), c.getInt(5) == 1, c.getInt(6) == 1,
                c.getString(7), if (c.isNull(1)) null else c.getString(1)
            ))
            rows.reverse()
            rows
        }

    /**
     * Lightweight complete history for trip and charging-session reconstruction.
     * These analyzers need only timestamps, SOC and odometer; loading sensor JSON
     * for every historical row wastes memory and needlessly limits the history window.
     */
    fun analyticsHistorySnapshots(): List<TelemetrySnapshot> =
        readableDatabase.rawQuery(
            "SELECT captured_at,soc,odometer FROM snapshots ORDER BY id ASC", null
        ).use { c ->
            val rows = mutableListOf<TelemetrySnapshot>()
            while (c.moveToNext()) rows.add(
                TelemetrySnapshot(
                    capturedAt = c.getString(0),
                    soc = c.getDouble(1),
                    odometer = c.getDouble(2),
                    stockRange = null,
                    online = false,
                    ready = false,
                    sensorsJson = "{}"
                )
            )
            rows
        }

    fun latest(): String {
        val item = latestSnapshot() ?: return "Пока нет записей"
        return "Время: ${item.capturedAt}\nSOC: ${item.soc}%\nОдометр: ${item.odometer} км\nШтатный запас: ${item.stockRange?.let { "${it} км" } ?: "—"}\nОнлайн: ${item.online}; готовность: ${item.ready}"
    }

    fun exportCsv(out: java.io.OutputStream) {
        out.writer(Charsets.UTF_8).use { w ->
            w.write("\uFEFF")
            w.write("captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json\r\n")
            readableDatabase.rawQuery(
                "SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,sensors_json FROM snapshots ORDER BY id", null
            ).use { c ->
                while (c.moveToNext()) {
                    val row = (0..7).joinToString(",") { i ->
                        if (c.isNull(i)) "" else csvField(c.getString(i))
                    }
                    w.write(row + "\r\n")
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: SnapshotDb? = null
        fun get(c: Context) = instance ?: synchronized(this) {
            instance ?: SnapshotDb(c.applicationContext).also { instance = it }
        }
        internal fun resetForTests() = synchronized(this) {
            instance?.close(); instance = null
        }
    }
}
