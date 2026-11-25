package com.evolute.telemetrycollector.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Validates and converts one raw read-only telemetry API response. */
internal object TelemetryParser {
    // Retain unknown sensor values for analysis, excluding GPS and vehicle/account identifiers.
    private val excludedSensorKeys = setOf(
        "lat", "lon", "lng", "latitude", "longitude",
        "gpslat", "gpslon", "gpslng", "gpslatitude", "gpslongitude",
        "imei", "vin", "carid", "vehicleid", "userid", "deviceid",
        "token", "accesstoken", "refreshtoken", "sessionid"
    )

    private fun sanitizedSensorsJson(sensors: JSONObject): String {
        fun clean(value: Any?): Any? = when (value) {
            is JSONObject -> {
                val safe = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val normalized = key.lowercase().filter { it.isLetterOrDigit() }
                    if (normalized in excludedSensorKeys) continue
                    val item = value.opt(key)
                    if (item != null && item != JSONObject.NULL) safe.put(key, clean(item))
                }
                safe
            }
            is JSONArray -> {
                val safe = JSONArray()
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    safe.put(if (item == JSONObject.NULL) JSONObject.NULL else clean(item))
                }
                safe
            }
            else -> value
        }
        return (clean(sensors) as JSONObject).toString()
    }

    fun parse(tel: JSONObject): ApiClient.Telemetry {
        val sensors = tel.optJSONObject("sensors")
            ?: throw IOException("В ответе нет sensors")

        fun number(key: String): Double? {
            val raw = sensors.opt(key) ?: return null
            if (raw == JSONObject.NULL || raw is Boolean) return null
            val value = raw.toString().toDoubleOrNull() ?: return null
            return value.takeIf { it.isFinite() }
        }

        fun nonNegative(key: String): Double? =
            number(key)?.takeIf { it >= 0.0 }

        fun bool(key: String): Boolean? {
            val raw = sensors.opt(key) ?: return null
            if (raw == JSONObject.NULL) return null
            return when (raw) {
                is Boolean -> raw
                else -> when (raw.toString().trim().lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }
        }

        val soc = nonNegative("batteryPercentage")
            ?.takeIf { it <= 100.0 }
            ?: throw IOException("SOC недоступен или вне диапазона 0–100")
        val odometer = nonNegative("odometer")
            ?: throw IOException("Одометр недоступен")
        val updatedAt = tel.opt("carStateUpdatedAt")
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }

        return ApiClient.Telemetry(
            soc = soc,
            odometer = odometer,
            stockRange = nonNegative("remainsBatteryMileage"),
            online = tel.optBoolean("isOnline"),
            ready = tel.optBoolean("isCarStateReady"),
            updatedAt = updatedAt,
            batteryTemp = number("batteryTemp"),
            voltage12v = nonNegative("12VBatteryVoltage"),
            coolantTemp = number("coolantTemp"),
            outsideTemp = number("outsideTemp"),
            cabinTemp = number("inBoardTemp"),
            climateTargetTemp = number("climateTargetTemp"),
            climateFanSpeed = nonNegative("climateFanSpeed"),
            chargingGunInserted = bool("isChargingGunInserted"),
            parked = bool("isParkedOn"),
            centralLockingOn = bool("isCentralLockingOn"),
            ignitionOn = bool("isIgnitionOn"),
            trunkOpen = bool("trunkStatus"),
            signalLevel = nonNegative("signalLevel"),
            firmwareVersion = sensors.optString("firmwareVersion")
                .takeIf { it.isNotBlank() && it != "null" },
            rawSensorsJson = sanitizedSensorsJson(sensors)
        )
    }
}
