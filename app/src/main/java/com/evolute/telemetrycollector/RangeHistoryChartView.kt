package com.evolute.telemetrycollector

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.evolute.telemetrycollector.data.RangeEstimator
import com.evolute.telemetrycollector.data.TelemetrySnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Historical comparison of the vehicle's range and the local SOC-based estimate. */
internal class RangeHistoryChartView(context: Context) : View(context) {
    private val stockColor = android.graphics.Color.rgb(96, 165, 250)
    private val calculatedColor = android.graphics.Color.rgb(91, 218, 193)
    private val labelColor = android.graphics.Color.rgb(151, 172, 181)
    private val stockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stockColor; strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val calculatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = calculatedColor; strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val stockGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stockColor; strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(3f)), 0f)
    }
    private val calculatedGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = calculatedColor; strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(3f)), 0f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(54, 70, 78); strokeWidth = dp(1f)
    }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor; textSize = dp(9f)
    }
    private val stockValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stockColor; textSize = dp(10f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val calculatedValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = calculatedColor; textSize = dp(10f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private var stock: List<Double?> = emptyList()
    private var calculated: List<Double?> = emptyList()
    private var times: List<Long> = emptyList()

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setSnapshots(history: List<TelemetrySnapshot>) {
        val samples = history.takeLast(240)
        times = samples.mapIndexed { index, sample ->
            try { Instant.parse(sample.capturedAt).toEpochMilli() }
            catch (_: Exception) { index * 30_000L }
        }
        stock = samples.map { it.stockRange?.takeIf { value -> value.isFinite() && value >= 0.0 } }
        calculated = samples.mapIndexed { index, _ ->
            RangeEstimator.estimate(samples.take(index + 1))?.takeIf { it.isFinite() && it >= 0.0 }
        }
        invalidate()
    }

    private fun timeLabel(timestamp: Long, longPeriod: Boolean): String {
        val pattern = if (longPeriod) "dd.MM HH:mm" else "HH:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val all = (stock + calculated).filterNotNull()
        if (all.size < 2 || times.size < 2) {
            labels.textAlign = Paint.Align.CENTER
            canvas.drawText("История запаса хода появится по мере сбора данных",
                width / 2f, height / 2f, labels)
            return
        }

        val rawMin = all.minOrNull() ?: 0.0
        val rawMax = all.maxOrNull() ?: 1.0
        val rawSpan = max(rawMax - rawMin, max(abs(rawMax) * 0.1, 10.0))
        val low = (rawMin - rawSpan * 0.1).coerceAtLeast(0.0)
        val high = max(rawMax + rawSpan * 0.1, low + 1.0)
        val axisLabels = (0..4).map { i ->
            String.format(Locale.getDefault(), "%.0f", high - (high - low) * i / 4.0)
        }
        val axisWidth = axisLabels.maxOf { labels.measureText(it) }
        val latestWidth = max(
            stock.lastOrNull { it != null }?.let { stockValue.measureText(String.format(Locale.getDefault(), "%.0f", it)) } ?: 0f,
            calculated.lastOrNull { it != null }?.let { calculatedValue.measureText(String.format(Locale.getDefault(), "%.0f", it)) } ?: 0f
        ) + dp(4f)
        val left = dp(8f) + axisWidth + dp(8f)
        val right = width - latestWidth - dp(14f)
        val top = dp(28f)
        val bottom = height - dp(30f)
        if (right <= left || bottom <= top) return

        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, grid)
            labels.textAlign = Paint.Align.RIGHT
            canvas.drawText(axisLabels[i], left - dp(7f), y + dp(3f), labels)
        }

        val firstTime = times.first()
        val timeSpan = max(1L, times.last() - firstTime)
        fun xFor(index: Int) = left + ((times[index] - firstTime).toDouble() / timeSpan).toFloat() * (right - left)
        fun yFor(value: Double) = bottom - ((value - low) / (high - low)).toFloat() * (bottom - top)

        fun drawSeries(values: List<Double?>, paint: Paint) {
            val path = Path()
            var started = false
            values.forEachIndexed { index, value ->
                if (value == null) started = false
                else {
                    if (index > 0 && times[index] - times[index - 1] > 10 * 60 * 1000L) started = false
                    val x = xFor(index)
                    val y = yFor(value)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, paint)
        }
        drawSeries(stock, stockPaint)
        drawSeries(calculated, calculatedPaint)

        fun drawLatest(values: List<Double?>, guide: Paint, valuePaint: Paint, offset: Int) {
            val index = values.indexOfLast { it != null }
            if (index < 0) return
            val value = values[index] ?: return
            val y = yFor(value)
            canvas.drawLine(left, y, right, y, guide)
            canvas.drawText(String.format(Locale.getDefault(), "%.0f", value),
                width - dp(6f), (y - dp(5f) - offset * dp(10f)).coerceAtLeast(dp(10f)), valuePaint)
        }
        drawLatest(stock, stockGuide, stockValue, 0)
        drawLatest(calculated, calculatedGuide, calculatedValue, 1)

        val longPeriod = timeSpan > 24 * 60 * 60 * 1000L
        val tickIndexes = listOf(0, times.size / 2, times.lastIndex).distinct()
        tickIndexes.forEachIndexed { position, index ->
            labels.textAlign = when (position) {
                0 -> Paint.Align.LEFT
                tickIndexes.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(timeLabel(times[index], longPeriod), xFor(index), height - dp(5f), labels)
        }
    }
}
