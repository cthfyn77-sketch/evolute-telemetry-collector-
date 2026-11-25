package com.evolute.telemetrycollector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class SnapshotDb private constructor(c: Context) : SQLiteOpenHelper(c,"evolute_collector.sqlite",null,3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE snapshots(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            captured_at TEXT NOT NULL,
            api_updated_at TEXT,
            soc REAL NOT NULL,
            odometer REAL NOT NULL,
            stock_range REAL,
            online INTEGER NOT NULL,
            ready INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_snapshots_captured ON snapshots(captured_at)")
        createRangeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRangeTables(db)
        if (oldVersion == 2) {
            // Version 2 was an unreleased development schema; rebuild only its
            // derived range tables. The original telemetry snapshots are retained.
            db.execSQL("DROP TABLE IF EXISTS range_km_samples")
            db.execSQL("DROP TABLE IF EXISTS range_estimate")
            createRangeTables(db)
        }
    }

    private fun createRangeTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS range_km_samples(
            km_index INTEGER PRIMARY KEY,
            odometer_mark REAL NOT NULL,
            soc_at_mark REAL NOT NULL,
            soc_consumed REAL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS range_estimate(
            id INTEGER PRIMARY KEY CHECK(id=1),
            range_km REAL NOT NULL,
            consumption_per_km REAL NOT NULL,
            updated_at_odometer REAL NOT NULL
        )""")
    }

    /**
     * Starts from the first observed odometer value, interpolates SOC at each
     * successive 1 km boundary, and publishes a moving average after every 5 km.
     */
    fun insert(t: ApiClient.Telemetry) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery(
                "SELECT soc,odometer FROM snapshots ORDER BY id DESC LIMIT 1", null
            ).use { c ->
                if (c.moveToFirst()) c.getDouble(0) to c.getDouble(1) else null
            }

            val v=ContentValues().apply {
                put("captured_at",java.time.Instant.now().toString())
                put("api_updated_at",t.updatedAt)
                put("soc",t.soc)
                put("odometer",t.odometer)
                put("stock_range",t.stockRange)
                put("online",if(t.online) 1 else 0)
                put("ready",if(t.ready) 1 else 0)
            }
            db.insertOrThrow("snapshots",null,v)

            if (previous == null) {
                insertRangeAnchor(db, 0L, t.odometer, t.soc)
            } else {
                processKilometres(db, previous.second, previous.first, t.odometer, t.soc)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertRangeAnchor(db: SQLiteDatabase, index: Long, odometer: Double, soc: Double) {
        val anchor = ContentValues().apply {
            put("km_index", index)
            put("odometer_mark", odometer)
            put("soc_at_mark", soc)
            putNull("soc_consumed")
        }
        db.insertWithOnConflict("range_km_samples", null, anchor, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun processKilometres(
        db: SQLiteDatabase,
        previousOdometer: Double,
        previousSoc: Double,
        currentOdometer: Double,
        currentSoc: Double
    ) {
        if (currentOdometer < previousOdometer - 0.01) {
            // An odometer reset or rollback starts a new independent range history.
            db.delete("range_km_samples", null, null)
            db.delete("range_estimate", null, null)
            insertRangeAnchor(db, 0L, currentOdometer, currentSoc)
            return
        }
        // Unchanged odometer readings are normal between 30-second polls.
        if (currentOdometer <= previousOdometer) return

        var last = db.rawQuery(
            "SELECT km_index,odometer_mark,soc_at_mark FROM range_km_samples ORDER BY km_index DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getLong(0), c.getDouble(1), c.getDouble(2)) else null
        } ?: run {
            insertRangeAnchor(db, 0L, previousOdometer, previousSoc)
            Triple(0L, previousOdometer, previousSoc)
        }

        while (last.second + 1.0 <= currentOdometer + 0.000001) {
            val boundaryOdometer = last.second + 1.0
            val fraction = ((boundaryOdometer - previousOdometer) /
                (currentOdometer - previousOdometer)).coerceIn(0.0, 1.0)
            val socAtBoundary = previousSoc + (currentSoc - previousSoc) * fraction
            val deltaSoc = last.third - socAtBoundary
            val consumed = deltaSoc.takeIf { it >= 0.0 && it <= 5.0 }
            val nextIndex = last.first + 1L

            val sample = ContentValues().apply {
                put("km_index", nextIndex)
                put("odometer_mark", boundaryOdometer)
                put("soc_at_mark", socAtBoundary)
                if (consumed == null) putNull("soc_consumed") else put("soc_consumed", consumed)
            }
            db.insertWithOnConflict("range_km_samples", null, sample, SQLiteDatabase.CONFLICT_REPLACE)
            last = Triple(nextIndex, boundaryOdometer, socAtBoundary)

            if (nextIndex % 5L == 0L) {
                val recent: List<Double>? = db.rawQuery(
                    "SELECT soc_consumed FROM range_km_samples WHERE km_index>? AND km_index<=? ORDER BY km_index",
                    arrayOf((nextIndex - 5L).toString(), nextIndex.toString())
                ).use { c ->
                    val values = mutableListOf<Double>()
                    while (c.moveToNext()) {
                        if (c.isNull(0)) return@use null
                        values.add(c.getDouble(0))
                    }
                    if (values.size == 5) values else null
                }
                if (recent != null && recent.average() > 0.0001) {
                    val average = recent.average()
                    val estimate = (socAtBoundary / average).coerceAtLeast(0.0)
                    val values = ContentValues().apply {
                        put("id", 1)
                        put("range_km", estimate)
                        put("consumption_per_km", average)
                        put("updated_at_odometer", boundaryOdometer)
                    }
                    db.insertWithOnConflict("range_estimate", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                } else {
                    // Do not keep showing a stale forecast after an invalid/charging interval.
                    db.delete("range_estimate", null, null)
                }
            }
        }
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots",null).use { it.moveToFirst(); it.getLong(0) }

    fun latest(): String = readableDatabase.rawQuery(
        "SELECT captured_at,soc,odometer,stock_range,online,ready FROM snapshots ORDER BY id DESC LIMIT 1",null
    ).use {
        if(!it.moveToFirst()) return "Пока нет записей"
        val stock = if(it.isNull(3)) "—" else formatKm(it.getDouble(3))
        val estimate = readableDatabase.rawQuery(
            "SELECT range_km,consumption_per_km,updated_at_odometer FROM range_estimate WHERE id=1", null
        ).use { e ->
            if (e.moveToFirst()) {
                "Расчётный запас: ${formatKm(e.getDouble(0))} км (сглаженный расход ${String.format(Locale.US, "%.3f", e.getDouble(1))}%/км; обновлён на одометре ${formatKm(e.getDouble(2))} км)"
            } else {
                "Расчётный запас: накопление данных, первое сглаживание через 5 км"
            }
        }
        "Время: ${it.getString(0)}\nSOC: ${it.getDouble(1)}%\nОдометр: ${it.getDouble(2)} км\n$estimate\nШтатный запас: ${if(stock == "—") stock else "$stock км"}\nОнлайн: ${it.getInt(4)==1}; готовность: ${it.getInt(5)==1}"
    }

    private fun formatKm(value: Double): String = String.format(Locale.US, "%.1f", value)

    fun exportCsv(out: java.io.OutputStream) {
        out.writer(Charsets.UTF_8).use { w ->
            w.write("captured_at,api_updated_at,soc,odometer,stock_range,online,ready\n")
            readableDatabase.rawQuery("SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready FROM snapshots ORDER BY id",null).use { c ->
                while(c.moveToNext()) {
                    val row=(0..6).joinToString(",") { i -> if(c.isNull(i)) "" else c.getString(i) }
                    w.write(row+"\n")
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
            instance?.close()
            instance = null
        }
    }
}
