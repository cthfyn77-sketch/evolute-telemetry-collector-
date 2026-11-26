package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

data class HistoryChartSeries(
    val label: String,
    val color: Int,
    val points: List<Pair<Float, Float>>
)

/** Compact line chart for historical battery/range values. */
class HistoryChartView(context: Context) : View(context) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78)
        strokeWidth = dp(1f)
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(151, 172, 181)
        textSize = dp(9f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var series: List<HistoryChartSeries> = emptyList()
    private var emptyMessage = "Недостаточно данных"

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setSeries(next: List<HistoryChartSeries>, message: String = "Недостаточно данных") {
        series = next.filter { it.points.isNotEmpty() }
        emptyMessage = message
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(38f)
        val right = width - dp(8f)
        val top = dp(12f)
        val bottom = height - dp(25f)
        if (right <= left || bottom <= top) return

        val all = series.flatMap { it.points }
        if (all.isEmpty()) {
            axis.textAlign = Paint.Align.CENTER
            axis.textSize = dp(11f)
            canvas.drawText(emptyMessage, width / 2f, height / 2f, axis)
            return
        }

        val minY = minOf(0f, all.minOf { it.second })
        val maxY = max(all.maxOf { it.second } * 1.12f, minY + 1f)
        val minX = all.minOf { it.first }
        val maxX = max(all.maxOf { it.first }, minX + 1f)
        fun x(value: Float) = left + ((value - minX) / (maxX - minX)) * (right - left)
        fun y(value: Float) = bottom - ((value - minY) / (maxY - minY)) * (bottom - top)

        for (i in 0..4) {
            val fraction = i / 4f
            val yy = bottom - fraction * (bottom - top)
            canvas.drawLine(left, yy, right, yy, grid)
            axis.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", minY + fraction * (maxY - minY)),
                left - dp(6f), yy + dp(3f), axis)
        }

        series.forEach { item ->
            line.color = item.color
            dot.color = item.color
            val path = Path()
            item.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.first), y(point.second))
                else path.lineTo(x(point.first), y(point.second))
            }
            canvas.drawPath(path, line)
            if (item.points.size == 1) {
                val point = item.points.first()
                canvas.drawCircle(x(point.first), y(point.second), dp(3f), dot)
            }
        }
        axis.textAlign = Paint.Align.LEFT
        canvas.drawText("НАЧАЛО", left, height - dp(6f), axis)
        axis.textAlign = Paint.Align.RIGHT
        canvas.drawText("СЕЙЧАС", right, height - dp(6f), axis)
    }
}
