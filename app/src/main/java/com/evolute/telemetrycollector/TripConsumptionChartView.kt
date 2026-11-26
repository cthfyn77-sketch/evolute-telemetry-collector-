package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.evolute.telemetrycollector.data.TripConsumptionPoint
import kotlin.math.max

/** Compact bar chart of measured SOC consumption for the latest completed trips. */
internal class TripConsumptionChartView(context: Context) : View(context) {
    private var points: List<TripConsumptionPoint> = emptyList()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(91, 218, 193)
        style = Paint.Style.FILL
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78)
        strokeWidth = dp(1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(151, 172, 181)
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val valueLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(241, 247, 248)
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }
    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setPoints(next: List<TripConsumptionPoint>) {
        points = next.takeLast(8)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("Появится после накопления поездок с надёжным SOC", width / 2f, height / 2f, label)
            return
        }
        val left = dp(8f)
        val right = width - dp(8f)
        val top = dp(18f)
        val bottom = height - dp(24f)
        if (right <= left || bottom <= top) return
        val maxValue = max(points.maxOf { it.percentPer100Km }, 1.0) * 1.15
        for (i in 0..2) {
            val y = bottom - (i / 2f) * (bottom - top)
            canvas.drawLine(left, y, right, y, grid)
        }
        val slot = (right - left) / points.size
        val barWidth = (slot * 0.52f).coerceAtLeast(dp(8f))
        points.forEachIndexed { index, point ->
            val center = left + slot * (index + 0.5f)
            val heightPx = ((point.percentPer100Km / maxValue).toFloat() * (bottom - top)).coerceAtLeast(dp(2f))
            canvas.drawRoundRect(center - barWidth / 2f, bottom - heightPx,
                center + barWidth / 2f, bottom, dp(3f), dp(3f), bar)
            canvas.drawText(String.format(java.util.Locale.US, "%.1f", point.percentPer100Km),
                center, (bottom - heightPx - dp(4f)).coerceAtLeast(top - dp(2f)), valueLabel)
            val date = try {
                java.time.format.DateTimeFormatter.ofPattern("dd.MM")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.parse(point.startedAt))
            } catch (_: Exception) { "${index + 1}" }
            canvas.drawText(date, center, height - dp(6f), label)
        }
    }
}
