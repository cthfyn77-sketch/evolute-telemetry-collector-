package com.evolute.telemetrycollector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SnapshotDb private constructor(c: Context) : SQLiteOpenHelper(c, "evolute_collector.sqlite", null, 3) {
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
            battery_temp REAL,
            voltage_12v REAL,
            coolant_temp REAL,
            outside_temp REAL,
            cabin_temp REAL,
            climate_target_temp REAL,
            climate_fan_speed REAL,
            charging_gun_inserted INTEGER,
            parked INTEGER,
            central_locking_on INTEGER,
            ignition_on INTEGER,
            trunk_open INTEGER,
            signal_level REAL,
            firmware_version TEXT,
            raw_sensors_json TEXT NOT NULL DEFAULT '{}'
        )""")
        db.execSQL("CREATE INDEX idx_snapshots_captured ON snapshots(captured_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE snapshots ADD COLUMN battery_temp REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN voltage_12v REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN coolant_temp REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN outside_temp REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN cabin_temp REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN climate_target_temp REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN climate_fan_speed REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN charging_gun_inserted INTEGER")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN parked INTEGER")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN central_locking_on INTEGER")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN ignition_on INTEGER")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN trunk_open INTEGER")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN signal_level REAL")
            db.execSQL("ALTER TABLE snapshots ADD COLUMN firmware_version TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE snapshots ADD COLUMN raw_sensors_json TEXT NOT NULL DEFAULT '{}'")
        }
    }

    fun insert(t: ApiClient.Telemetry) {
        val v = ContentValues().apply {
            put("captured_at", java.time.Instant.now().toString())
            put("api_updated_at", t.updatedAt)
            put("soc", t.soc)
            put("odometer", t.odometer)
            put("stock_range", t.stockRange)
            put("online", if (t.online) 1 else 0)
            put("ready", if (t.ready) 1 else 0)
            put("battery_temp", t.batteryTemp)
            put("voltage_12v", t.voltage12v)
            put("coolant_temp", t.coolantTemp)
            put("outside_temp", t.outsideTemp)
            put("cabin_temp", t.cabinTemp)
            put("climate_target_temp", t.climateTargetTemp)
            put("climate_fan_speed", t.climateFanSpeed)
            put("charging_gun_inserted", t.chargingGunInserted?.let { if (it) 1 else 0 })
            put("parked", t.parked?.let { if (it) 1 else 0 })
            put("central_locking_on", t.centralLockingOn?.let { if (it) 1 else 0 })
            put("ignition_on", t.ignitionOn?.let { if (it) 1 else 0 })
            put("trunk_open", t.trunkOpen?.let { if (it) 1 else 0 })
            put("signal_level", t.signalLevel)
            put("firmware_version", t.firmwareVersion)
            put("raw_sensors_json", t.rawSensorsJson)
        }
        writableDatabase.insertOrThrow("snapshots", null, v)
    }

    /** Latest sanitized raw sensor object, used by the Pro field-discovery panel. */
    fun latestRawSensors(): String = readableDatabase.rawQuery(
        "SELECT raw_sensors_json FROM snapshots ORDER BY id DESC LIMIT 1",
        null
    ).use { c ->
        if (!c.moveToFirst()) return "Пока нет данных"
        try {
            org.json.JSONObject(c.getString(0)).toString(2)
        } catch (_: Exception) {
            "Последний пакет датчиков не удалось разобрать"
        }
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots", null).use {
        it.moveToFirst()
        it.getLong(0)
    }

    fun latest(): String = readableDatabase.rawQuery(
        """SELECT captured_at,soc,odometer,stock_range,online,ready,
            battery_temp,voltage_12v,coolant_temp,outside_temp,cabin_temp,
            climate_target_temp,climate_fan_speed,charging_gun_inserted,
            parked,central_locking_on,ignition_on,trunk_open,signal_level,firmware_version
            FROM snapshots ORDER BY id DESC LIMIT 1""",
        null
    ).use { c ->
        if (!c.moveToFirst()) return "Пока нет записей"
        val lines = mutableListOf(
            "Время: ${c.getString(0)}",
            "SOC: ${c.getDouble(1)}%",
            "Одометр: ${c.getDouble(2)} км",
            "Штатный запас: ${if (c.isNull(3)) "—" else c.getDouble(3).toString() + " км"}",
            "Онлайн: ${c.getInt(4) == 1}; готовность: ${c.getInt(5) == 1}"
        )
        fun addNumber(label: String, index: Int, suffix: String = "") {
            if (!c.isNull(index)) lines.add("$label: ${c.getDouble(index)}$suffix")
        }
        fun addBoolean(label: String, index: Int) {
            if (!c.isNull(index)) lines.add("$label: ${c.getInt(index) == 1}")
        }
        addNumber("Температура батареи", 6, " °C")
        addNumber("Напряжение 12 В", 7, " В")
        addNumber("Температура охлаждающей жидкости", 8, " °C")
        addNumber("Температура снаружи", 9, " °C")
        addNumber("Температура салона", 10, " °C")
        addNumber("Уставка климата", 11, " °C")
        addNumber("Скорость вентилятора", 12)
        addBoolean("Зарядный разъём подключён", 13)
        addBoolean("Автомобиль припаркован", 14)
        addBoolean("Центральный замок", 15)
        addBoolean("Зажигание включено", 16)
        addBoolean("Багажник открыт", 17)
        addNumber("Уровень сигнала API", 18)
        if (!c.isNull(19)) lines.add("Версия прошивки: ${c.getString(19)}")
        lines.joinToString("\n")
    }

    fun exportCsv(out: java.io.OutputStream) {
        out.writer(Charsets.UTF_8).use { w ->
            w.write("captured_at,api_updated_at,soc,odometer,stock_range,online,ready,battery_temp,voltage_12v,coolant_temp,outside_temp,cabin_temp,climate_target_temp,climate_fan_speed,charging_gun_inserted,parked,central_locking_on,ignition_on,trunk_open,signal_level,firmware_version,raw_sensors_json\n")
            readableDatabase.rawQuery(
                """SELECT captured_at,api_updated_at,soc,odometer,stock_range,online,ready,
                    battery_temp,voltage_12v,coolant_temp,outside_temp,cabin_temp,
                    climate_target_temp,climate_fan_speed,charging_gun_inserted,
                    parked,central_locking_on,ignition_on,trunk_open,signal_level,firmware_version,raw_sensors_json
                    FROM snapshots ORDER BY id""",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val row = (0 until c.columnCount).joinToString(",") { i ->
                        if (c.isNull(i)) "" else csvEscape(c.getString(i))
                    }
                    w.write(row + "\n")
                }
            }
        }
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

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
