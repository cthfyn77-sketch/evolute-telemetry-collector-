package com.evolute.telemetrycollector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SnapshotDb private constructor(c: Context) : SQLiteOpenHelper(c,"evolute_collector.sqlite",null,1) {
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
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun insert(t: ApiClient.Telemetry) {
        val v=ContentValues().apply {
            put("captured_at",java.time.Instant.now().toString()); put("api_updated_at",t.updatedAt)
            put("soc",t.soc); put("odometer",t.odometer); put("stock_range",t.stockRange)
            put("online",if(t.online) 1 else 0); put("ready",if(t.ready) 1 else 0)
        }
        writableDatabase.insertOrThrow("snapshots",null,v)
    }
    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots",null).use { it.moveToFirst(); it.getLong(0) }
    fun latest(): String = readableDatabase.rawQuery("SELECT captured_at,soc,odometer,stock_range,online,ready FROM snapshots ORDER BY id DESC LIMIT 1",null).use {
        if(!it.moveToFirst()) return "Пока нет записей"
        "Время: ${it.getString(0)}\nSOC: ${it.getDouble(1)}%\nОдометр: ${it.getDouble(2)} км\nШтатный запас: ${if(it.isNull(3)) "—" else it.getDouble(3).toString()+" км"}\nОнлайн: ${it.getInt(4)==1}; готовность: ${it.getInt(5)==1}"
    }
    fun exportCsv(out: java.io.OutputStream) {
        out.writer(Charsets.UTF_8).use { w ->
            w.write("captured_at,api_updated_at,soc,odometer,stock_range,online,ready\n")
            readableDatabase.rawQuery("SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready FROM snapshots ORDER BY id",null).use { c ->
                while(c.moveToNext()) {
                    val row=(0..6).joinToString(",") { i -> if(c.isNull(i)) "" else c.getString(i).replace("\"",""\"\"").let { "\"$it\"" } }
                    w.write(row+"\n")
                }
            }
        }
    }
    companion object { @Volatile private var instance: SnapshotDb?=null; fun get(c:Context)=instance ?: synchronized(this) { instance ?: SnapshotDb(c.applicationContext).also { instance=it } } }
}
