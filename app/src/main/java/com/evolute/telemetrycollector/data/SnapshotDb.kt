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

            if (previous != null) {
                processKilometres(db, previous.second, previous.first, t.odometer, t.soc)
            } else if (kotlin.math.abs(t.odometer - floor(t.odometer)) < 0.000001) {
                val anchor = ContentValues().apply {
                    put("km_mark", floor(t.odometer).toLong())
                    put("soc_at_mark", t.soc)
                    putNull("soc_consumed")
                }
                db.insertWithOnConflict("range_km_samples", null, anchor, SQLiteDatabase.CONFLICT_REPLACE)
            }
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
        if (currentOdometer < previousOdometer - 0.01) {
            // An odometer reset or rollback must not join two unrelated journeys.
            db.delete("range_km_samples", null, null)
            db.delete("range_estimate", null, null)
            return
        }
        // Unchanged odometer readings are normal between 30-second polls.
        if (currentOdometer <= previousOdometer) return
