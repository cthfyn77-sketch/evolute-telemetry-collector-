package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.evolute.telemetrycollector.data.BatteryHistoryAnalyzer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Time-series plot for one numeric EVACONNECT metric, with explicit scale and latest reading. */
internal class BatteryMetricChartView(context: Context) : View(context) {
    private var points: List<BatteryHistoryAnalyzer.MetricPoint> = emptyList()
    private var mileageAxis = false
    private var metricPath = ""

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(91, 218, 193)
        style = Paint.Style.STROKE; strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val latestGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = line.color; style = Paint.Style.STROKE; strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(3f)), 0f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78); strokeWidth = dp(1f)
    }
    private val zeroGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(108, 139, 148); strokeWidth = dp(1.3f)
    }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(151, 172, 181); textSize = dp(9f)
    }
    private val valueLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = line.color; textSize = dp(10f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line.color; style = Paint.Style.FILL }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setPoints(next: List<BatteryHistoryAnalyzer.MetricPoint>, path: String = "") {
        mileageAxis = false
        metricPath = path
        points = next.sortedBy { it.at }.takeLast(480)
        invalidate()
    }

    fun setMileagePoints(next: List<BatteryHistoryAnalyzer.MetricPoint>) {
        mileageAxis = true
        points = next.filter { it.odometer != null && it.odometer.isFinite() && it.odometer >= 0.0 }
            .sortedBy { it.odometer }.takeLast(480)
        invalidate()
    }

    private fun key() = metricPath.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    private fun isSoc(k: String) = k == "soc" || k.contains("stateofcharge") || k.contains("chargelevel")
    private fun isVoltage(k: String) = k.contains("voltage") || k.contains("volt")
    private fun isTemperature(k: String) = k.contains("temperature") || k.contains("temp") ||
        k.contains("outside") || k.contains("ambient")
    private fun number(value: Double, decimals: Int): String =
        String.format(Locale.getDefault(), if (decimals == 0) "%.0f" else if (decimals == 1) "%.1f" else "%.2f", value)

    private fun latestText(value: Double, k: String): String = when {
        isSoc(k) -> number(value, 0) + "%"
        isVoltage(k) -> number(value, 1) + " В"
        isTemperature(k) -> (if (value > 0.0) "+" else "") + number(value, 1) + " °C"
        else -> number(value, 2)
    }

    private fun timeLabel(at: Instant, longPeriod: Boolean): String {
        val pattern = if (longPeriod) "dd.MM HH:mm" else "HH:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(at)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val k = key()
        val soc = isSoc(k)
        val voltage = isVoltage(k)
        val temperature = isTemperature(k)
        val rawMin = points.minOf { it.value }
        val rawMax = points.maxOf { it.value }
        val (low, high) = when {
            soc -> 0.0 to 100.0
            voltage && rawMin >= 0.0 && rawMax <= 17.0 -> 0.0 to 17.0
            temperature -> {
                val bound = max(5.0, ceil(max(abs(rawMin), abs(rawMax)) / 5.0) * 5.0)
                -bound to bound
            }
            else -> {
                val span = max(rawMax - rawMin, max(abs(rawMax) * 0.01, 0.1))
                (rawMin - span * 0.08) to (rawMax + span * 0.08)
            }
        }
        fun axisNumber(value: Double): String = when {
            soc -> number(value, 0)
            voltage -> number(value, 1)
            temperature -> (if (value > 0.0) "+" else "") + number(value, 0)
            else -> number(value, 2)
        }

        val tickValues = (0..4).map { i -> high - (high - low) * i / 4.0 }
        val tickLabels = tickValues.map(::axisNumber)
        val axisWidth = tickLabels.maxOf { labels.measureText(it) }
        val currentWidth = valueLabel.measureText(latestText(points.last().value, k)) + dp(4f)
        val left = dp(8f) + axisWidth + dp(8f)
        val right = width - currentWidth - dp(14f)
        val top = dp(28f)
        val bottom = height - dp(32f)
        if (right <= left || bottom <= top) return

        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y,
                if (temperature && abs(tickValues[i]) < 0.0001) zeroGrid else grid)
            labels.textAlign = Paint.Align.RIGHT
            canvas.drawText(tickLabels[i], left - dp(7f), y + dp(3f), labels)
        }

        val xValues = if (mileageAxis) points.map { it.odometer ?: 0.0 }
            else points.map { it.at.toEpochMilli().toDouble() }
        val xStart = xValues.first()
        val xSpan = max(xValues.last() - xStart, 1.0)
        fun xFor(index: Int) = left + ((xValues[index] - xStart) / xSpan).toFloat() * (right - left)
        fun yFor(value: Double) = bottom - ((value - low) / (high - low)).toFloat() * (bottom - top)

        val path = Path()
        points.forEachIndexed { index, point ->
            val gap = index > 0 &&
                Duration.between(points[index - 1].at, point.at).seconds > 10 * 60L
            val x = xFor(index)
            val y = yFor(point.value)
            if (index == 0 || gap) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
        val latest = points.last()
        val latestY = yFor(latest.value)
        canvas.drawCircle(xFor(points.lastIndex), latestY, dp(3f), dot)
        canvas.drawLine(left, latestY, right, latestY, latestGuide)
        canvas.drawText(latestText(latest.value, k), width - dp(6f),
            (latestY - dp(5f)).coerceAtLeast(dp(10f)), valueLabel)

        val longPeriod = !mileageAxis &&
            Duration.between(points.first().at, points.last().at).toHours() >= 24
        val tickIndexes = listOf(0, points.size / 2, points.lastIndex).distinct()
        tickIndexes.forEachIndexed { position, index ->
            labels.textAlign = when (position) {
                0 -> Paint.Align.LEFT
                tickIndexes.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val label = if (mileageAxis) number(xValues[index], 1) + " км"
                else timeLabel(points[index].at, longPeriod)
            canvas.drawText(label, xFor(index), height - dp(5f), labels)
        }
    }
}
