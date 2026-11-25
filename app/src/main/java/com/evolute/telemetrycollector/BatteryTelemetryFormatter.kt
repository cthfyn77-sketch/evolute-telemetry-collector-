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
    /**
     * Formats the latest sensor payload while requiring three consistent samples
     * before accepting a new SOH value. A single server-side spike cannot replace
     * the last confirmed state of health.
     */
    fun format(history: List<com.evolute.telemetrycollector.data.TelemetrySnapshot>, maxLines: Int = 16): String {
        if (history.isEmpty()) return "EVACONNECT пока не передал дополнительные параметры батареи"
        val latest = history.last()
        val base = format(latest.sensorsJson, maxLines)
        val latestSoh = extractSohValues(latest.sensorsJson)
        if (latestSoh.isEmpty()) return base

        val states = mutableMapOf<String, SohState>()
        var previousAt: java.time.Instant? = null
        for (sample in history) {
            val currentAt = try { java.time.Instant.parse(sample.capturedAt) } catch (_: Exception) { null }
            if (previousAt != null && currentAt != null &&
                java.time.Duration.between(previousAt, currentAt).toMillis() > 3 * 60 * 1000L) {
                states.values.forEach { it.candidate = null; it.candidateCount = 0 }
            }
            previousAt = currentAt ?: previousAt
            val readings = extractSohValues(sample.sensorsJson)
            for ((path, value) in readings) {
                val state = states.getOrPut(path) { SohState() }
                val confirmed = state.confirmed
                if (confirmed == null || kotlin.math.abs(value - confirmed) > 1.0) {
                    if (state.candidate != null && kotlin.math.abs(value - state.candidate!!) <= 0.5) {
                        state.candidateCount++
                    } else {
                        state.candidate = value
                        state.candidateCount = 1
                    }
                    if (state.candidateCount >= 3) {
                        state.confirmed = state.candidate
                        state.candidate = null
                        state.candidateCount = 0
                    }
                } else {
                    state.candidate = null
                    state.candidateCount = 0
                    if (state.confirmed == null) state.confirmed = value
                }
            }
        }

        return base.lines().map { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) return@map line
            val path = line.substring(0, separator)
            val normalized = normalizePath(path)
            val matchedPath = latestSoh.keys.firstOrNull { normalizePath(it) == normalized } ?: return@map line
            val rawValue = latestSoh[matchedPath] ?: return@map line
            val confirmed = states[matchedPath]?.confirmed
            when {
                confirmed == null -> "$path: проверяется (получено: $rawValue)"
                kotlin.math.abs(rawValue - confirmed) > 1.0 ->
                    "$path: $confirmed · новое значение проверяется"
                else -> "$path: $confirmed"
            }
        }.joinToString("\n")
    }

    private class SohState {
        var confirmed: Double? = null
        var candidate: Double? = null
        var candidateCount: Int = 0
    }

    private fun normalizePath(path: String): String =
        path.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    private fun extractSohValues(raw: String): Map<String, Double> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val result = linkedMapOf<String, Double>()
            fun walk(value: Any?, path: String, depth: Int) {
                if (depth > 6) return
                when (value) {
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val next = if (path.isEmpty()) key else "$path.$key"
                            walk(value.opt(key), next, depth + 1)
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until minOf(value.length(), 12)) {
                            walk(value.opt(i), "$path[$i]", depth + 1)
                        }
                    }
                    null, JSONObject.NULL -> Unit
                    else -> {
                        val normalized = normalizePath(path)
                        val isSoh = normalized.contains("soh") ||
                            normalized.contains("stateofhealth") || normalized.contains("batteryhealth")
                        val number = value.toString().removeSuffix("%").toDoubleOrNull()
                        if (isSoh && number != null && number.isFinite() && number in 0.0..100.0) {
                            result[path] = number
                        }
                    }
                }
            }
            walk(root, "", 0)
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

}
