package com.evolute.telemetrycollector.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Read-only history view over sensor JSON already stored in snapshots. */
internal object BatteryHistoryAnalyzer {
    data class MetricPoint(val at: Instant, val value: Double)
    data class MetricHistory(
        val path: String,
        val latest: Double,
        val min: Double,
        val max: Double,
        val samples: Int,
        val points: List<MetricPoint>
    )
    data class Report(val metrics: List<MetricHistory>, val alerts: List<String>)
    private val terms = listOf("voltage", "volt", "temperature", "temp", "soh", "stateofhealth", "batteryhealth", "current", "amp", "soc", "stateofcharge", "chargelevel")

    fun analyze(history: List<TelemetrySnapshot>): Report {
        val points = linkedMapOf<String, MutableList<MetricPoint>>()
        val sensorSocExists = history.any { sample ->
            extract(sample.sensorsJson).keys.any { key ->
                key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").let {
                    it.contains("soc") || it.contains("stateofcharge") || it.contains("chargelevel")
                }
            }
        }
        history.forEach { sample ->
            val at = try { Instant.parse(sample.capturedAt) } catch (_: Exception) { null } ?: return@forEach
            val sensorValues = extract(sample.sensorsJson)
            sensorValues.forEach { (path, value) ->
                points.getOrPut(path) { mutableListOf() }.add(MetricPoint(at, value))
            }
            // SOC is stored as a first-class snapshot field, even when EVACONNECT
            // does not expose it again inside the nested sensor JSON.
            val sensorHasSoc = sensorValues.keys.any { key ->
                key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").let {
                    it.contains("soc") || it.contains("stateofcharge") || it.contains("chargelevel")
                }
            }
            if (!sensorSocExists && sample.soc.isFinite() && sample.soc in 0.0..100.0) {
                points.getOrPut("SOC") { mutableListOf() }.add(MetricPoint(at, sample.soc))
            }
        }
        val metrics = points.mapNotNull { (path, rawValues) ->
            val values = rawValues.sortedBy { it.at }
            if (values.isEmpty()) null else MetricHistory(
                path, values.last().value, values.minOf { it.value },
                values.maxOf { it.value }, values.size, values.toList()
            )
        }.sortedBy { it.path.lowercase(Locale.ROOT) }
        val alerts = mutableListOf<String>()
        points.forEach { (path, values) ->
            for (i in 1 until values.lastIndex) {
                val before = values[i - 1]; val spike = values[i]; val after = values[i + 1]
                if (Duration.between(before.at, spike.at).toMinutes() > 3 || Duration.between(spike.at, after.at).toMinutes() > 3) continue
                val baseline = before.value
                val deviates = abs(spike.value - baseline) >= max(abs(baseline) * 0.20, 1.0)
                val returns = abs(after.value - baseline) <= max(abs(baseline) * 0.05, 0.5)
                if (deviates && returns) {
                    alerts.add(path + ": одиночный скачок " + fmt(spike.value) + " между " + fmt(before.value) + " и " + fmt(after.value) + " — проверьте качество телеметрии")
                    break
                }
            }
        }
        return Report(metrics, alerts.take(8))
    }

    fun render(report: Report): String {
        if (report.metrics.isEmpty()) return "История появится после получения числовых датчиков напряжения, тока, температуры или SOH."
        val lines = report.metrics.take(12).map { m ->
            val delta = m.points.last().value - m.points.first().value
            val change = if (m.points.size >= 2) " · изменение за период " + signed(delta) else ""
            m.path + ": сейчас " + fmt(m.latest) + " · диапазон " + fmt(m.min) + "–" + fmt(m.max) + change + " · " + m.samples + " замеров"
        }.toMutableList()
        if (report.metrics.size > 12) lines.add("… ещё " + (report.metrics.size - 12) + " параметров")
        lines.add("")
        if (report.alerts.isNotEmpty()) { lines.add("СОБЫТИЯ КАЧЕСТВА ДАННЫХ"); lines.addAll(report.alerts) }
        else lines.add("Подтверждённых одиночных скачков в сохранённой истории не обнаружено.")
        return lines.joinToString("\n")
    }

    private fun extract(raw: String): Map<String, Double> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val result = linkedMapOf<String, Double>()
            fun walk(value: Any?, path: String, depth: Int) {
                if (depth > 6 || result.size >= 300) return
                when (value) {
                    is JSONObject -> { val keys = value.keys(); while (keys.hasNext()) { val key = keys.next(); walk(value.opt(key), if (path.isEmpty()) key else path + "." + key, depth + 1) } }
                    is JSONArray -> for (i in 0 until minOf(value.length(), 12)) walk(value.opt(i), path + "[" + i + "]", depth + 1)
                    null, JSONObject.NULL, is Boolean -> Unit
                    else -> {
                        val normalized = path.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                        if (terms.any { normalized.contains(it) }) {
                            val number = value.toString().removeSuffix("%").toDoubleOrNull()
                            if (number != null && number.isFinite()) result[path] = number
                        }
                    }
                }
            }
            walk(JSONObject(raw), "", 0); result
        } catch (_: Exception) { emptyMap() }
    }
    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun signed(value: Double): String = (if (value > 0) "+" else "") + fmt(value)
}