package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Lightweight native SOC history chart; values are read-only local snapshots. */
class TelemetryChartView(context: Context) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(91, 218, 193)
        strokeWidth = dp(2f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(34, 91, 218, 193)
        style = Paint.Style.FILL
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78)
        strokeWidth = dp(1f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(91, 218, 193)
        style = Paint.Style.FILL
    }
    private var values: List<Float> = emptyList()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setValues(next: List<Float>) {
        values = next.takeLast(24)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(5f); val right = width - dp(5f)
        val top = dp(8f); val bottom = height - dp(10f)
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, grid)
        }
        if (values.isEmpty()) return
        val low = max(0f, (values.minOrNull() ?: 0f) - 3f)
        val high = min(100f, (values.maxOrNull() ?: 100f) + 3f).let { if (it - low < 8f) min(100f, low + 8f) else it }
        val range = max(1f, high - low)
        val points = values.mapIndexed { index, value ->
            val x = if (values.size == 1) (left + right) / 2f else left + (right - left) * index / (values.size - 1)
            val y = bottom - ((value.coerceIn(low, high) - low) / range) * (bottom - top)
            x to y
        }
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
        }
        val area = Path(path).apply {
            lineTo(points.last().first, bottom)
            lineTo(points.first().first, bottom)
            close()
        }
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        val last = points.last()
        canvas.drawCircle(last.first, last.second, dp(4f), dot)
    }
}
