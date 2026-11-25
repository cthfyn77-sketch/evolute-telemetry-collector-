package com.evolute.telemetrycollector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import kotlin.math.floor

class SnapshotDb private constructor(c: Context) : SQLiteOpenHelper(c,"evolute_collector.sqlite",null,2) {
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
    }

    private fun createRangeTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS range_km_samples(
            km_mark INTEGER PRIMARY KEY,
            soc_at_mark REAL NOT NULL,
            soc_consumed REAL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS range_estimate(
            id INTEGER PRIMARY KEY CHECK(id=1),
            range_km REAL NOT NULL,
            consumption_per_km REAL NOT NULL,
            updated_at_km INTEGER NOT NULL
        )""")
    }

    /**
     * Stores every telemetry snapshot and interpolates SOC at each whole-kilometre
     * odometer mark. A fresh smoothed estimate is published only at each 5 km mark.
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

            if (previous != null) {\n                processKilometres(db, previous.second, previous.first, t.odometer, t.soc)\n            } else if (kotlin.math.abs(t.odometer - floor(t.odometer)) < 0.000001) {\n                val anchor = ContentValues().apply { put("km_mark", floor(t.odometer).toLong()); put("soc_at_mark", t.soc); putNull("soc_consumed") }\n                db.insertWithOnConflict("range_km_samples", null, anchor, SQLiteDatabase.CONFLICT_REPLACE)\n            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun processKilometres(
        db: SQLiteDatabase,
        previousOdometer: Double,
        previousSoc: Double,
        currentOdometer: Double,
        currentSoc: Double
    ) {
        if (currentOdometer <= previousOdometer) {
            // An odometer reset or rollback must not join two unrelated journeys.
            db.delete("range_km_samples", null, null)
            db.delete("range_estimate", null, null)
            return
        }

        val firstMark = floor(previousOdometer).toLong() + 1L
        val lastMark = floor(currentOdometer).toLong()
        if (lastMark < firstMark) return

        var priorBoundarySoc: Double? = db.rawQuery(
            "SELECT soc_at_mark FROM range_km_samples ORDER BY km_mark DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else null }
        var priorBoundaryMark: Long? = db.rawQuery(
            "SELECT km_mark FROM range_km_samples ORDER BY km_mark DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        for (mark in firstMark..lastMark) {
            val fraction = ((mark.toDouble() - previousOdometer) /
                (currentOdometer - previousOdometer)).coerceIn(0.0, 1.0)
            val socAtMark = previousSoc + (currentSoc - previousSoc) * fraction
            val consumed = if (priorBoundaryMark == mark - 1L && priorBoundarySoc != null) {
                val delta = priorBoundarySoc - socAtMark
                // Ignore charge increases and implausible SOC jumps; zero is valid
                // because the vehicle API may report SOC in rounded percentage points.
                delta.takeIf { it >= 0.0 && it <= 5.0 }
            } else null

            val sample = ContentValues().apply {
                put("km_mark", mark)
                put("soc_at_mark", socAtMark)
                if (consumed == null) putNull("soc_consumed") else put("soc_consumed", consumed)
            }
            db.insertWithOnConflict("range_km_samples", null, sample, SQLiteDatabase.CONFLICT_REPLACE)
            priorBoundarySoc = socAtMark
            priorBoundaryMark = mark

            if (mark % 5L == 0L) {
                val recent = db.rawQuery(
                    "SELECT soc_consumed FROM range_km_samples WHERE km_mark>? AND km_mark<=? ORDER BY km_mark",
                    arrayOf((mark - 5L).toString(), mark.toString())
                ).use { c ->
                    val values = mutableListOf<Double>()
                    while (c.moveToNext()) {
                        if (c.isNull(0)) return@use null
                        values.add(c.getDouble(0))
                    }
                    if (values.size == 5) values else null
                }
                if (recent != null) {
                    val average = recent.average()
                    if (average > 0.0001) {
                        val estimate = (socAtMark / average).coerceAtLeast(0.0)
                        val values = ContentValues().apply {
                            put("id", 1)
                            put("range_km", estimate)
                            put("consumption_per_km", average)
                            put("updated_at_km", mark)
                        }
                        db.insertWithOnConflict("range_estimate", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    }
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
            "SELECT range_km,consumption_per_km,updated_at_km FROM range_estimate WHERE id=1", null
        ).use { e ->
            if (e.moveToFirst()) {
                "Расчётный запас: ${formatKm(e.getDouble(0))} км (сглаженный расход ${String.format(Locale.US, "%.3f", e.getDouble(1))}%/км; обновлён на ${e.getLong(2)} км одометра)"
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
