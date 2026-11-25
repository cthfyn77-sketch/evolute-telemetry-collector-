package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import com.evolute.telemetrycollector.data.TelemetrySnapshot
import kotlin.math.abs
import kotlin.math.max

/**
 * One SOC history chart per independent horizontal axis.
 * DISTANCE plots SOC against odometer; TIME plots SOC against capture time.
 * Charging periods are highlighted only on the time chart.
 */
class TelemetryChartView(
    context: Context,
    private val axisMode: AxisMode
) : View(context) {

    enum class AxisMode { DISTANCE, TIME }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (axisMode == AxisMode.DISTANCE) {
            android.graphics.Color.rgb(91, 218, 193)
        } else {
            android.graphics.Color.rgb(96, 165, 250)
        }
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chargeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(42, 245, 158, 11)
        style = Paint.Style.FILL
    }
    private val chargeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(245, 158, 11)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val threshold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(239, 68, 68)
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78)
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
    }
    private val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(8f)
        strokeCap = Paint.Cap.ROUND
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(151, 172, 181)
        textSize = dp(9f)
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL
        )
    }

    private var snapshots: List<TelemetrySnapshot> = emptyList()
    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setSnapshots(next: List<TelemetrySnapshot>) {
        snapshots = if (axisMode == AxisMode.TIME) {
            next.takeLast(240)
        } else {
            // Keep the full local mileage history; collapse repeated parked samples.
            val byDistance = mutableListOf<TelemetrySnapshot>()
            next.forEach { sample ->
                val previous = byDistance.lastOrNull()
                when {
                    previous == null -> byDistance.add(sample)
                    sample.odometer > previous.odometer + 0.001 -> byDistance.add(sample)
                    abs(sample.odometer - previous.odometer) <= 0.001 -> byDistance[byDistance.lastIndex] = sample
                    // Ignore stale/backfilled odometer regressions.
                }
            }
            byDistance.takeLast(2000)
        }
        invalidate()
    }

    private fun timestamp(snapshot: TelemetrySnapshot, fallback: Int): Long =
        try {
            java.time.Instant.parse(snapshot.capturedAt).toEpochMilli()
        } catch (_: Exception) {
            fallback.toLong()
        }

    private fun chargingSessions(): List<Pair<Int, Int>> {
        val sessions = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < snapshots.size) {
            var end = start
            while (end + 1 < snapshots.size &&
                abs(snapshots[end + 1].odometer - snapshots[start].odometer) <= 0.01
            ) end++
            if (end > start) {
                val socs = snapshots.subList(start, end + 1).map { it.soc }
                if ((socs.maxOrNull() ?: 0.0) - (socs.minOrNull() ?: 0.0) >= 0.2 &&
                    (socs.last() - socs.first()) >= 0.2
                ) sessions.add(start to end)
            }
            start = end + 1
        }
        return sessions
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (snapshots.size < 2) {
            axisText.color = android.graphics.Color.rgb(151, 172, 181)
            axisText.textAlign = Paint.Align.CENTER
            axisText.textSize = dp(11f)
            val message = if (axisMode == AxisMode.DISTANCE) {
                "История пробега появится после движения"
            } else {
                "Ожидание временной истории SOC"
            }
            canvas.drawText(message, width / 2f, height / 2f, axisText)
            return
        }

        val left = dp(13f)
        val right = width - dp(7f)
        val top = dp(8f)
        val bottom = height - dp(25f)
        if (right <= left || bottom <= top) return

        scale.shader = LinearGradient(
            0f, bottom, 0f, top,
            intArrayOf(
                android.graphics.Color.rgb(239, 68, 68),
                android.graphics.Color.rgb(249, 115, 22),
                android.graphics.Color.rgb(234, 179, 8),
                android.graphics.Color.rgb(34, 197, 94)
            ),
            floatArrayOf(0f, 0.35f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(left - dp(7f), top, left - dp(7f), bottom, scale)

        for (level in listOf(0f, 25f, 50f, 75f, 100f)) {
            val y = bottom - (level / 100f) * (bottom - top)
            canvas.drawLine(left, y, right, y, grid)
        }
        val thresholdY = bottom - 0.15f * (bottom - top)
        canvas.drawLine(left, thresholdY, right, thresholdY, threshold)

        val times = snapshots.mapIndexed { index, item -> timestamp(item, index) }
        val firstTime = times.first()
        val timeSpan = max(1L, times.last() - firstTime)
        val minOdo = snapshots.minOf { it.odometer }
        val distanceSpan = max(0.001, snapshots.maxOf { it.odometer } - minOdo)

        fun yFor(soc: Double) =
            bottom - (soc.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)

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
                if (x2 - x1 > dp(30f)) {
                    canvas.drawText("ЗАРЯДКА", x1 + dp(3f), top + dp(10f), axisText)
                }
            }
        }

        val path = Path()
        snapshots.forEachIndexed { index, item ->
            val x = xFor(index)
            val y = yFor(item.soc)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        axisText.color = android.graphics.Color.rgb(151, 172, 181)
        axisText.textAlign = Paint.Align.LEFT
        axisText.textSize = dp(9f)
        canvas.drawText("SOC, %", left, height - dp(6f), axisText)
        axisText.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            if (axisMode == AxisMode.DISTANCE) "ПРОБЕГ, КМ" else "ВРЕМЯ",
            right, height - dp(6f), axisText
        )
    }
}
