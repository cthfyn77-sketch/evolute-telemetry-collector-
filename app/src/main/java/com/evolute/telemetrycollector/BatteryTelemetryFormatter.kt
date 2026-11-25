package com.evolute.telemetrycollector

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Shows only battery-related fields actually present in the EVACONNECT payload.
 * Values and units are deliberately kept exactly as supplied by the API.
 */
internal object BatteryTelemetryFormatter {
    private val batteryTerms = listOf(
        "battery", "cell", "voltage", "current", "temperature", "temp",
        "soh", "health", "power", "charge", "charging"
    )
    private val excludedTerms = listOf("batterypercentage", "remainsbatterymileage")

    fun format(raw: String, maxLines: Int = 16): String {
        if (raw.isBlank()) return "EVACONNECT пока не передал дополнительные параметры батареи"
        return try {
            val root = JSONObject(raw)
            val values = mutableListOf<String>()
            fun walk(value: Any?, path: String, depth: Int) {
                if (values.size >= maxLines.coerceAtLeast(1) || depth > 6) return
                when (value) {
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext() && values.size < maxLines.coerceAtLeast(1)) {
                            val key = keys.next()
                            val nextPath = if (path.isEmpty()) key else "$path.$key"
                            walk(value.opt(key), nextPath, depth + 1)
                        }
                    }
                    is JSONArray -> {
                        if (pathMatches(path) && value.length() in 1..12) {
                            val items = (0 until value.length()).mapNotNull { index ->
                                val item = value.opt(index)
                                if (item == null || item == JSONObject.NULL || item is JSONObject || item is JSONArray) null
                                else item.toString()
                            }
                            if (items.isNotEmpty()) values.add("$path: ${items.joinToString(", ")}")
                        } else {
                            for (index in 0 until minOf(value.length(), 12)) {
                                walk(value.opt(index), "$path[$index]", depth + 1)
                            }
                        }
                    }
                    null, JSONObject.NULL -> Unit
                    else -> if (pathMatches(path)) values.add("$path: ${value}")
                }
            }
            walk(root, "", 0)
            when {
                values.isEmpty() -> "Дополнительные параметры батареи не переданы"
                else -> values.joinToString("\n") +
                    if (values.size >= maxLines.coerceAtLeast(1)) "\n… показаны первые $maxLines параметров" else ""
            }
        } catch (_: Exception) {
            "Не удалось разобрать параметры батареи из ответа EVACONNECT"
        }
    }

    private fun pathMatches(path: String): Boolean {
        val normalized = path.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        if (excludedTerms.any { normalized.contains(it) }) return false
        return batteryTerms.any { normalized.contains(it) }
    }
}
