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
import kotlin.math.max
import kotlin.math.abs

/**
 * SOC by distance (green) and time (blue).
 * Stationary SOC increases are highlighted as charging sessions on the time axis.
 */
class TelemetryChartView(context: Context) : View(context) {
    private val socByDistance = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(34, 197, 94)
        strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val socByTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(96, 165, 250)
        strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val chargeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(42, 245, 158, 11)
        style = Paint.Style.FILL
    }
    private val chargeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(245, 158, 11)
        strokeWidth = dp(1f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val threshold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(239, 68, 68)
        strokeWidth = dp(1.5f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78)
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
    }
    private val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(8f); strokeCap = Paint.Cap.ROUND
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(151, 172, 181)
        textSize = dp(9f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private var snapshots: List<TelemetrySnapshot> = emptyList()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setSnapshots(next: List<TelemetrySnapshot>) {
        snapshots = next.takeLast(240)
        invalidate()
    }

    private fun timestamp(snapshot: TelemetrySnapshot, fallback: Int): Long =
        try { java.time.Instant.parse(snapshot.capturedAt).toEpochMilli() } catch (_: Exception) { fallback.toLong() }

    private fun hasMileageMovement(): Boolean {
        if (snapshots.size < 2) return false
        val values = snapshots.map { it.odometer }
        return (values.maxOrNull()!! - values.minOrNull()!!) > 0.001
    }

    /** A stationary contiguous group with a measurable SOC rise is a charging session. */
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
                    (socs.last() - socs.first()) >= 0.2) {
                    sessions.add(start to end)
                }
            }
            start = end + 1
        }
        return sessions
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (snapshots.size < 2) return
        val left = dp(13f)
        val right = width - dp(7f)
        val top = dp(8f)
        val bottom = height - dp(25f)
        if (right <= left || bottom <= top) return

        scale.shader = LinearGradient(0f, bottom, 0f, top,
            intArrayOf(android.graphics.Color.rgb(239, 68, 68), android.graphics.Color.rgb(249, 115, 22),
                android.graphics.Color.rgb(234, 179, 8), android.graphics.Color.rgb(34, 197, 94)),
            floatArrayOf(0f, 0.35f, 0.68f, 1f), Shader.TileMode.CLAMP)
        canvas.drawLine(left - dp(7f), top, left - dp(7f), bottom, scale)
        for (level in listOf(0f, 25f, 50f, 75f, 100f)) {
            val y = bottom - (level / 100f) * (bottom - top)
            canvas.drawLine(left, y, right, y, grid)
        }
        val thresholdY = bottom - 0.15f * (bottom - top)
        canvas.drawLine(left, thresholdY, right, thresholdY, threshold)

        val times = snapshots.mapIndexed { i, item -> timestamp(item, i) }
        val firstTime = times.first()
        val lastTime = times.last()
        val timeSpan = max(1L, lastTime - firstTime)
        val minOdo = snapshots.minOf { it.odometer }
        val maxOdo = snapshots.maxOf { it.odometer }
        val distanceSpan = max(0.001, maxOdo - minOdo)
        fun yFor(soc: Double) = bottom - (soc.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)
        fun timeX(index: Int): Float =
            left + ((times[index] - firstTime).toDouble() / timeSpan).toFloat() * (right - left)

        // Highlight charge windows behind the time curve, bounded by their observed timestamps.
        chargingSessions().forEach { (from, to) ->
            val x1 = timeX(from)
            val x2 = timeX(to).coerceAtLeast(x1 + dp(3f))
            canvas.drawRect(x1, top, x2, bottom, chargeFill)
            canvas.drawLine(x1, top, x1, bottom, chargeEdge)
            canvas.drawLine(x2, top, x2, bottom, chargeEdge)
            axisText.color = android.graphics.Color.rgb(245, 158, 11)
            axisText.textAlign = Paint.Align.LEFT
            if (x2 - x1 > dp(30f)) canvas.drawText("ЗАРЯДКА", x1 + dp(3f), top + dp(10f), axisText)
        }

        val timePath = Path()
        snapshots.forEachIndexed { index, item ->
            val y = yFor(item.soc)
            if (index == 0) timePath.moveTo(timeX(index), y) else timePath.lineTo(timeX(index), y)
        }
        canvas.drawPath(timePath, socByTime)

        if (hasMileageMovement()) {
            // Preserve historical SOC colors by coloring each segment from its recorded levels.
            for (index in 1 until snapshots.size) {
                val previous = snapshots[index - 1]
                val current = snapshots[index]
                val x1 = left + ((previous.odometer - minOdo) / distanceSpan).toFloat() * (right - left)
                val x2 = left + ((current.odometer - minOdo) / distanceSpan).toFloat() * (right - left)
                val y1 = yFor(previous.soc)
                val y2 = yFor(current.soc)
                val segmentSoc = (previous.soc + current.soc) / 2.0
                socByDistance.color = when {
                    segmentSoc >= 80.0 -> android.graphics.Color.rgb(34, 197, 94)
                    segmentSoc >= 60.0 -> android.graphics.Color.rgb(132, 204, 22)
                    segmentSoc >= 40.0 -> android.graphics.Color.rgb(234, 179, 8)
                    segmentSoc >= 20.0 -> android.graphics.Color.rgb(249, 115, 22)
                    else -> android.graphics.Color.rgb(239, 68, 68)
                }
                canvas.drawLine(x1, y1, x2, y2, socByDistance)
            }
        }

        axisText.color = android.graphics.Color.rgb(151, 172, 181)
        axisText.textAlign = Paint.Align.LEFT
        if (hasMileageMovement()) canvas.drawText("ПРОБЕГ, КМ", left, height - dp(6f), axisText)
        axisText.textAlign = Paint.Align.RIGHT
        canvas.drawText("ВРЕМЯ", right, height - dp(6f), axisText)
    }
}
