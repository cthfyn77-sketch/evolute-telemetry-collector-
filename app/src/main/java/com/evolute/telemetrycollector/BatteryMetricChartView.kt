package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.evolute.telemetrycollector.data.BatteryHistoryAnalyzer
import kotlin.math.max

/** Small time-series plot for one numeric battery sensor supplied by EVACONNECT. */
internal class BatteryMetricChartView(context: Context) : View(context) {
    private var points: List<BatteryHistoryAnalyzer.MetricPoint> = emptyList()
    private var mileageAxis = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(91, 218, 193)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78)
        strokeWidth = dp(1f)
    }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(151, 172, 181)
        textSize = dp(9f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(91, 218, 193)
        style = Paint.Style.FILL
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setPoints(next: List<BatteryHistoryAnalyzer.MetricPoint>) {
        mileageAxis = false
        points = next.takeLast(480)
        invalidate()
    }

    fun setMileagePoints(next: List<BatteryHistoryAnalyzer.MetricPoint>) {
        mileageAxis = true
        points = next.filter { it.odometer != null && it.odometer.isFinite() && it.odometer >= 0.0 }
            .takeLast(480)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = dp(38f)
        val right = width - dp(8f)
        val top = dp(8f)
        val bottom = height - dp(20f)
        if (right <= left || bottom <= top) return

        val minValue = points.minOf { it.value }
        val maxValue = points.maxOf { it.value }
        val span = max(maxValue - minValue, max(kotlin.math.abs(maxValue) * 0.01, 0.1))
        val low = minValue - span * 0.08
        val high = maxValue + span * 0.08
        val xValues = if (mileageAxis) points.mapNotNull { it.odometer } else points.map { it.at.toEpochMilli().toDouble() }
        if (xValues.size != points.size || xValues.size < 2) return
        val xStart = xValues.first()
        val xSpan = max(xValues.last() - xStart, 0.001)

        for (i in 0..2) {
            val fraction = i / 2f
            val y = bottom - fraction * (bottom - top)
            canvas.drawLine(left, y, right, y, grid)
            val value = low + (high - low) * fraction
            labels.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(java.util.Locale.US, "%.2f", value), left - dp(5f), y + dp(3f), labels)
        }

        val path = Path()
        points.forEachIndexed { index, point ->
            val xValue = if (mileageAxis) point.odometer ?: xStart else point.at.toEpochMilli().toDouble()
            val x = left + ((xValue - xStart) / xSpan).toFloat() * (right - left)
            val y = bottom - ((point.value - low) / (high - low)).toFloat() * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (index == points.lastIndex) canvas.drawCircle(x, y, dp(3f), dot)
        }
        canvas.drawPath(path, line)
        labels.textAlign = Paint.Align.LEFT
        canvas.drawText(if (mileageAxis) String.format(java.util.Locale.US, "%.1f км", xStart) else "РАНЬШЕ", left, height - dp(4f), labels)
        labels.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (mileageAxis) String.format(java.util.Locale.US, "%.1f км", xStart + xSpan) else "СЕЙЧАС", right, height - dp(4f), labels)
    }
}
