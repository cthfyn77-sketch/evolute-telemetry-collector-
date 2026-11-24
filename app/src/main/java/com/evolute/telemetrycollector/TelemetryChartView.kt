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

/**
 * Two read-only SOC series on one chart:
 * green = SOC by odometer, blue = SOC by elapsed time.
 * The 15% reserve threshold is always visible.
 */
class TelemetryChartView(context: Context) : View(context) {
    private val socByDistance = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(34, 197, 94)
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val socByTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(96, 165, 250)
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        strokeWidth = dp(8f) // approximately 2 mm on a standard-density display
        strokeCap = Paint.Cap.ROUND
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

    private fun hasMileageMovement(): Boolean {
        if (snapshots.size < 2) return false
        val values = snapshots.map { it.odometer }
        return (values.maxOrNull()!! - values.minOrNull()!!) > 0.001
    }

    private fun timestamp(snapshot: TelemetrySnapshot, fallback: Int): Long =
        try { java.time.Instant.parse(snapshot.capturedAt).toEpochMilli() } catch (_: Exception) { fallback.toLong() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasMileageMovement()) return

        val left = dp(13f)
        val right = width - dp(7f)
        val top = dp(8f)
        val bottom = height - dp(25f)
        if (right <= left || bottom <= top) return

        // A slim vertical color scale, without numbers or tick labels.
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

        // Faint guides only; the colored vertical line is the SOC scale.
        for (level in listOf(0f, 25f, 50f, 75f, 100f)) {
            val y = bottom - (level / 100f) * (bottom - top)
            canvas.drawLine(left, y, right, y, grid)
        }

        // Fixed 15% low-charge threshold.
        val thresholdY = bottom - 0.15f * (bottom - top)
        canvas.drawLine(left, thresholdY, right, thresholdY, threshold)

        val minOdo = snapshots.minOf { it.odometer }
        val maxOdo = snapshots.maxOf { it.odometer }
        val firstTime = timestamp(snapshots.first(), 0)
        val lastTime = timestamp(snapshots.last(), snapshots.size - 1)
        val timeSpan = max(1L, lastTime - firstTime)
        val distanceSpan = max(0.001, maxOdo - minOdo)

        fun yFor(soc: Double): Float =
            bottom - (soc.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)

        val distancePath = Path()
        val timePath = Path()
        snapshots.forEachIndexed { index, item ->
            val distanceX = left + ((item.odometer - minOdo) / distanceSpan).toFloat() * (right - left)
            val elapsed = timestamp(item, index) - firstTime
            val timeX = left + (elapsed.toDouble() / timeSpan).toFloat() * (right - left)
            val y = yFor(item.soc)
            if (index == 0) {
                distancePath.moveTo(distanceX, y)
                timePath.moveTo(timeX, y)
            } else {
                distancePath.lineTo(distanceX, y)
                timePath.lineTo(timeX, y)
            }
        }
        canvas.drawPath(distancePath, socByDistance)
        canvas.drawPath(timePath, socByTime)

        axisText.textAlign = Paint.Align.LEFT
        canvas.drawText("ПРОБЕГ, КМ", left, height - dp(6f), axisText)
        axisText.textAlign = Paint.Align.RIGHT
        canvas.drawText("ВРЕМЯ", right, height - dp(6f), axisText)
    }
}
