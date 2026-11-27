package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.evolute.telemetrycollector.data.TelemetrySnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * One SOC history chart per independent horizontal axis.
 * DISTANCE plots SOC against odometer; TIME plots SOC against capture time.
 * Charging periods are highlighted only on the time chart.
 */
class TelemetryChartView(context: Context, private val axisMode: AxisMode) : View(context) {
    enum class AxisMode { DISTANCE, TIME }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (axisMode == AxisMode.DISTANCE) android.graphics.Color.rgb(91, 218, 193)
            else android.graphics.Color.rgb(96, 165, 250)
        strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val chargeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(42, 245, 158, 11); style = Paint.Style.FILL
    }
    private val chargeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(245, 158, 11); strokeWidth = dp(1f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val threshold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(239, 68, 68); strokeWidth = dp(1f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val latestGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = linePaint.color; strokeWidth = dp(1f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(3f)), 0f)
    }
    private val latestDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = linePaint.color; style = Paint.Style.FILL
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78); strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(151, 172, 181); textSize = dp(9f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val latestValueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = linePaint.color; textSize = dp(10f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
    }

    private var snapshots: List<TelemetrySnapshot> = emptyList()
    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setSnapshots(next: List<TelemetrySnapshot>) {
        snapshots = if (axisMode == AxisMode.TIME) next.takeLast(240) else {
            val byDistance = mutableListOf<TelemetrySnapshot>()
            next.forEach { sample ->
                val previous = byDistance.lastOrNull()
                when {
                    previous == null -> byDistance.add(sample)
                    sample.odometer > previous.odometer + 0.001 -> byDistance.add(sample)
                    abs(sample.odometer - previous.odometer) <= 0.001 -> byDistance[byDistance.lastIndex] = sample
                }
            }
            byDistance.takeLast(2000)
        }
        invalidate()
    }

    private fun timestamp(snapshot: TelemetrySnapshot, fallback: Int): Long =
        try { Instant.parse(snapshot.capturedAt).toEpochMilli() } catch (_: Exception) { fallback * 30_000L }

    private fun timeLabel(timestamp: Long, longPeriod: Boolean): String {
        val pattern = if (longPeriod) "dd.MM HH:mm" else "HH:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
    }

    private fun chargingSessions(): List<Pair<Int, Int>> {
        val sessions = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < snapshots.size) {
            var end = start
            while (end + 1 < snapshots.size &&
                abs(snapshots[end + 1].odometer - snapshots[start].odometer) <= 0.01) end++
            if (end > start) {
                val socs = snapshots.subList(start, end + 1).map { it.soc }
                if ((socs.maxOrNull() ?: 0.0) - (socs.minOrNull() ?: 0.0) >= 0.2 &&
                    (socs.last() - socs.first()) >= 0.2) sessions.add(start to end)
            }
            start = end + 1
        }
        return sessions
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (snapshots.isEmpty()) {
            axisText.textAlign = Paint.Align.CENTER; axisText.textSize = dp(11f)
            canvas.drawText("Ожидание временной истории SOC", width / 2f, height / 2f, axisText)
            return
        }

        val left = dp(40f)
        val right = width - dp(56f)
        val top = dp(28f)
        val bottom = height - dp(32f)
        if (right <= left || bottom <= top) return

        listOf(100f, 75f, 50f, 25f, 0f).forEach { level ->
            val y = bottom - (level / 100f) * (bottom - top)
            canvas.drawLine(left, y, right, y, grid)
            axisText.textAlign = Paint.Align.RIGHT; axisText.textSize = dp(9f)
            canvas.drawText(level.toInt().toString(), left - dp(7f), y + dp(3f), axisText)
        }
        canvas.drawLine(left, bottom - 0.15f * (bottom - top), right,
            bottom - 0.15f * (bottom - top), threshold)

        val times = snapshots.mapIndexed { index, item -> timestamp(item, index) }
        val firstTime = times.first()
        val timeSpan = max(1L, times.last() - firstTime)
        val minOdo = snapshots.minOf { it.odometer }
        val distanceSpan = max(0.001, snapshots.maxOf { it.odometer } - minOdo)
        fun yFor(soc: Double) = bottom - (soc.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)
        fun xFor(index: Int): Float = if (axisMode == AxisMode.TIME) {
            left + ((times[index] - firstTime).toDouble() / timeSpan).toFloat() * (right - left)
        } else {
            left + ((snapshots[index].odometer - minOdo) / distanceSpan).toFloat() * (right - left)
        }

        if (axisMode == AxisMode.TIME) {
            chargingSessions().forEach { (from, to) ->
                val x1 = xFor(from)
                val x2 = xFor(to).coerceAtLeast(x1 + dp(3f))
                canvas.drawRect(x1, top, x2, bottom, chargeFill)
                canvas.drawLine(x1, top, x1, bottom, chargeEdge)
                canvas.drawLine(x2, top, x2, bottom, chargeEdge)
                axisText.color = android.graphics.Color.rgb(245, 158, 11)
                axisText.textAlign = Paint.Align.LEFT
                if (x2 - x1 > dp(30f)) canvas.drawText("ЗАРЯДКА", x1 + dp(3f), top + dp(10f), axisText)
            }
        }

        val path = Path()
        snapshots.forEachIndexed { index, item ->
            val x = xFor(index)
            val y = yFor(item.soc)
            val gap = axisMode == AxisMode.TIME && index > 0 &&
                times[index] - times[index - 1] > 10 * 60 * 1000L
            if (index == 0 || gap) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        val latest = snapshots.last()
        val latestY = yFor(latest.soc)
        if (axisMode == AxisMode.TIME) {
            canvas.drawLine(left, latestY, right, latestY, latestGuide)
            canvas.drawCircle(xFor(snapshots.lastIndex), latestY, dp(3f), latestDot)
            canvas.drawText(String.format(Locale.getDefault(), "%.0f%%", latest.soc),
                width - dp(6f), (latestY - dp(5f)).coerceAtLeast(dp(10f)), latestValueText)
        }

        axisText.color = android.graphics.Color.rgb(151, 172, 181)
        axisText.textSize = dp(8.5f); axisText.textAlign = Paint.Align.LEFT
        canvas.drawText("SOC, %", left, height - dp(5f), axisText)
        val tickIndexes = listOf(0, snapshots.size / 2, snapshots.lastIndex).distinct()
        val longPeriod = timeSpan > 24 * 60 * 60 * 1000L
        tickIndexes.forEachIndexed { position, index ->
            axisText.textAlign = when (position) {
                0 -> Paint.Align.LEFT
                tickIndexes.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val label = if (axisMode == AxisMode.TIME) timeLabel(times[index], longPeriod)
                else String.format(Locale.getDefault(), "%.1f км", snapshots[index].odometer)
            canvas.drawText(label, xFor(index), height - dp(5f), axisText)
        }
    }
}
