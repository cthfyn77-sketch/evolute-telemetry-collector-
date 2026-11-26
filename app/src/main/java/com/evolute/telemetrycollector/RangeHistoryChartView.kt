package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.evolute.telemetrycollector.data.RangeEstimator
import com.evolute.telemetrycollector.data.TelemetrySnapshot
import kotlin.math.max

/** Historical comparison of the vehicle's range and the local SOC-based estimate. */
internal class RangeHistoryChartView(context: Context) : View(context) {
    private val stockColor = android.graphics.Color.rgb(96, 165, 250)
    private val calculatedColor = android.graphics.Color.rgb(91, 218, 193)
    private val gridColor = android.graphics.Color.rgb(54, 70, 78)
    private val labelColor = android.graphics.Color.rgb(151, 172, 181)
    private val stockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stockColor; strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val calculatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = calculatedColor; strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor; strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor; textSize = dp(9f)
    }
    private var stock: List<Double?> = emptyList()
    private var calculated: List<Double?> = emptyList()

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setSnapshots(history: List<TelemetrySnapshot>) {
        val samples = history.takeLast(240)
        stock = samples.map { it.stockRange?.takeIf { value -> value.isFinite() && value >= 0.0 } }
        calculated = samples.mapIndexed { index, _ ->
            RangeEstimator.estimate(samples.take(index + 1))
                ?.takeIf { it.isFinite() && it >= 0.0 }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val all = (stock + calculated).filterNotNull()
        if (all.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("История запаса хода появится по мере сбора данных", width / 2f, height / 2f, textPaint)
            return
        }
        val left = dp(12f)
        val right = width - dp(8f)
        val top = dp(10f)
        val bottom = height - dp(22f)
        if (right <= left || bottom <= top) return
        val minValue = (all.minOrNull() ?: 0.0).let { (it * 0.9).coerceAtLeast(0.0) }
        val maxValue = max((all.maxOrNull() ?: 1.0) * 1.1, minValue + 1.0)
        val span = maxValue - minValue
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", maxValue - span * i / 4.0),
                left - dp(3f), y + dp(3f), textPaint)
        }
        fun drawSeries(values: List<Double?>, paint: Paint) {
            val path = Path()
            var started = false
            values.forEachIndexed { index, value ->
                if (value == null) {
                    started = false
                } else {
                    val x = if (values.size <= 1) left else left + index.toFloat() / (values.size - 1) * (right - left)
                    val y = bottom - ((value - minValue) / span).toFloat() * (bottom - top)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, paint)
        }
        drawSeries(stock, stockPaint)
        drawSeries(calculated, calculatedPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("ПОСЛЕДНИЕ ЗАПИСИ", left, height - dp(5f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("КМ", right, height - dp(5f), textPaint)
    }
}
