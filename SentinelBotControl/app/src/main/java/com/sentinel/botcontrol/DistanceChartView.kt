package com.sentinel.botcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * Rolling line chart of ultrasonic distance readings over the last [windowMs]
 * milliseconds (default 30s), matching the AHA sensors-dashboard design.
 */
class DistanceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val windowMs = 30_000L
    private val maxValue = 100f // y-axis is a fixed 0..100 cm scale, matching the design

    private data class Sample(val time: Long, val value: Int)
    private val samples = ArrayDeque<Sample>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = themeColor("grid_line")
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_secondary")
        textSize = 20f
        textLocale = Locale.US
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = themeColor("accent_cyan")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("accent_cyan")
        alpha = 30
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("text_primary")
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = themeColor("text_secondary")
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("bg_card_elevated")
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_primary")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        textLocale = Locale.US
    }

    private fun themeColor(name: String): Int {
        val resId = resources.getIdentifier(name, "color", context.packageName)
        return if (resId != 0) resources.getColor(resId, null) else Color.CYAN
    }

    fun addSample(distanceCm: Int) {
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, distanceCm.coerceIn(0, 400)))
        trim(now)
        invalidate()
    }

    fun clear() {
        samples.clear()
        invalidate()
    }

    private fun trim(now: Long) {
        val cutoff = now - windowMs
        while (samples.isNotEmpty() && samples.first().time < cutoff) samples.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leftPad = 46f
        val rightPad = 10f
        val topPad = 14f
        val bottomPad = 30f
        val chartRect = RectF(leftPad, topPad, width - rightPad, height - bottomPad)

        // y-axis grid + labels: 0, 25, 50, 75, 100
        for (i in 0..4) {
            val v = i * 25
            val y = chartRect.bottom - (v / maxValue) * chartRect.height()
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)
            canvas.drawText(v.toString(), 4f, y + 7f, axisTextPaint)
        }

        // x-axis labels: 30s .. 0s
        val labels = listOf("30s", "20s", "10s", "0s")
        for (i in labels.indices) {
            val fx = chartRect.left + (i / 3f) * chartRect.width()
            canvas.drawText(labels[i], fx - 12f, chartRect.bottom + 22f, axisTextPaint)
        }

        if (samples.size < 2) return

        val now = System.currentTimeMillis()
        trim(now)
        if (samples.size < 2) return

        fun xFor(t: Long): Float {
            val age = (now - t).coerceIn(0, windowMs)
            val frac = 1f - age.toFloat() / windowMs
            return chartRect.left + frac * chartRect.width()
        }
        fun yFor(v: Int): Float {
            val frac = (v.coerceIn(0, maxValue.toInt())) / maxValue
            return chartRect.bottom - frac * chartRect.height()
        }

        val linePath = Path()
        val fillPath = Path()
        samples.forEachIndexed { index, s ->
            val x = xFor(s.time)
            val y = yFor(s.value)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, chartRect.bottom)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        val lastX = xFor(samples.last().time)
        fillPath.lineTo(lastX, chartRect.bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // dots
        samples.forEach { s ->
            canvas.drawCircle(xFor(s.time), yFor(s.value), 3.5f, dotPaint)
        }

        // dashed average line
        val avg = samples.map { it.value }.average().toInt()
        val avgY = yFor(avg)
        canvas.drawLine(chartRect.left, avgY, chartRect.right, avgY, avgPaint)

        // tooltip on the latest point
        val last = samples.last()
        val tx = xFor(last.time)
        val ty = yFor(last.value)
        val label = "${last.value} cm"
        val textWidth = tooltipTextPaint.measureText(label)
        val bubbleW = textWidth + 24f
        val bubbleH = 34f
        var bubbleLeft = tx - bubbleW / 2f
        bubbleLeft = bubbleLeft.coerceIn(chartRect.left, chartRect.right - bubbleW)
        val bubbleTop = (ty - bubbleH - 14f).coerceAtLeast(topPad)
        val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleLeft + bubbleW, bubbleTop + bubbleH)
        canvas.drawRoundRect(bubbleRect, 8f, 8f, tooltipBgPaint)
        canvas.drawText(label, bubbleRect.centerX(), bubbleRect.centerY() + 7f, tooltipTextPaint)
    }
}
