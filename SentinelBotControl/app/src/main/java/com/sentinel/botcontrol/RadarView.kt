package com.sentinel.botcontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Sweeping radar display matching the robot's ultrasonic servo scan.
 * Firmware angle convention: 0°=right, 90°=straight ahead, 180°=left
 * (RADAR_MIN_ANGLE=20 .. RADAR_MAX_ANGLE=160).
 * On-screen we relabel that as a heading: -90° (left) .. 0° (ahead) .. +90° (right),
 * with a glowing "comet-tail" wedge trailing the sweep line and a center hub
 * showing the live distance reading, matching the AHA UI design.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var angleDeg: Int = 90
    private var distanceCm: Int = -1 // -1 = no reading yet
    private var hasData: Boolean = false
    private val maxRangeCm = 120

    // recent sweep angles, newest last — used to paint the fading trail wedge
    private val angleHistory = ArrayDeque<Int>()

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = themeColor("grid_line")
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = themeColor("accent_cyan")
    }
    private val sweepPaintIdle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = themeColor("accent_red")
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("accent_cyan")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_secondary")
        textSize = 24f
        textAlign = Paint.Align.CENTER
        textLocale = Locale.US // force Latin "-90/0/90" digits regardless of device numeral settings
    }
    private val baseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = themeColor("divider")
    }
    private val hubFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("bg_card_elevated")
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = themeColor("accent_cyan")
    }
    private val hubGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themeColor("accent_cyan")
    }
    private val hubLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_secondary")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val hubValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("text_primary")
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textLocale = Locale.US // force Latin digits for the distance number
    }
    private val hubUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor("accent_cyan")
        textSize = 18f
        textAlign = Paint.Align.CENTER
        textLocale = Locale.US
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("accent_green")
    }
    private val blipGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor("accent_green")
    }

    private fun themeColor(name: String): Int {
        val resId = resources.getIdentifier(name, "color", context.packageName)
        return if (resId != 0) resources.getColor(resId, null) else Color.CYAN
    }

    fun updateReading(angle: Int, distCm: Int) {
        angleDeg = angle.coerceIn(0, 180)
        distanceCm = distCm
        hasData = true
        angleHistory.addLast(angleDeg)
        if (angleHistory.size > 45) angleHistory.removeFirst()
        invalidate()
    }

    /** Called when the connection drops or before any reading has arrived. */
    fun clearData() {
        hasData = false
        distanceCm = -1
        angleHistory.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height - 14f
        val horizontalPadding = 24f
        val radius = min(width / 2f - horizontalPadding, height - 26f) - 8f

        for (i in 1..4) {
            val r = radius * i / 4f
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, 180f, false, arcPaint)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, baseLinePaint)

        for (a in 0..180 step 30) {
            val rad = Math.toRadians(a.toDouble())
            val x = cx + (cos(rad) * radius).toFloat()
            val y = cy - (sin(rad) * radius).toFloat()
            canvas.drawLine(cx, cy, x, y, arcPaint)
        }

        // fading "comet tail" wedge: thin slices behind the sweep line, each
        // fainter the further back in the recent sweep history it is
        if (hasData) {
            val n = angleHistory.size
            angleHistory.forEachIndexed { index, histAngle ->
                val progress = (index + 1).toFloat() / n // 0..1, 1 = current sweep
                val nextAngle = angleHistory.getOrNull(index + 1) ?: histAngle
                val path = Path()
                path.moveTo(cx, cy)
                val rad1 = Math.toRadians(histAngle.toDouble())
                val rad2 = Math.toRadians(nextAngle.toDouble())
                path.lineTo(cx + (cos(rad1) * radius).toFloat(), cy - (sin(rad1) * radius).toFloat())
                path.lineTo(cx + (cos(rad2) * radius).toFloat(), cy - (sin(rad2) * radius).toFloat())
                path.close()
                trailPaint.alpha = (progress * progress * 70).toInt().coerceIn(0, 70)
                canvas.drawPath(path, trailPaint)
            }
        }

        // sweep line — cyan while data is flowing, dim red when idle/no data
        val rad = Math.toRadians(angleDeg.toDouble())
        val sx = cx + (cos(rad) * radius).toFloat()
        val sy = cy - (sin(rad) * radius).toFloat()
        canvas.drawLine(cx, cy, sx, sy, if (hasData) sweepPaint else sweepPaintIdle)

        // heading labels: -90 (left) .. 0 (ahead) .. +90 (right)
        canvas.drawText("-90°", cx - radius, cy + 22f, labelPaint)
        canvas.drawText("0°", cx, cy - radius - 12f, labelPaint)
        canvas.drawText("90°", cx + radius, cy + 22f, labelPaint)

        // bright blip marking the actual detected object at its measured distance
        if (hasData && distanceCm in 1 until 400) {
            val br = (min(distanceCm, maxRangeCm).toFloat() / maxRangeCm) * radius
            val bx = cx + (cos(rad) * br).toFloat()
            val by = cy - (sin(rad) * br).toFloat()
            blipGlowPaint.alpha = 90
            canvas.drawCircle(bx, by, 13f, blipGlowPaint)
            canvas.drawCircle(bx, by, 6f, blipPaint)
        }

        // center hub with a soft multi-ring glow behind it, then the live distance readout
        val hubRadius = 54f
        for (ring in 3 downTo 1) {
            hubGlowPaint.strokeWidth = ring * 6f
            hubGlowPaint.alpha = (25 - ring * 6)
            canvas.drawCircle(cx, cy, hubRadius + ring * 5f, hubGlowPaint)
        }
        canvas.drawCircle(cx, cy, hubRadius, hubFillPaint)
        canvas.drawCircle(cx, cy, hubRadius, hubStrokePaint)
        canvas.drawText("فاصله", cx, cy - 16f, hubLabelPaint)
        val valueText = if (hasData && distanceCm in 1 until 400) "$distanceCm" else "--"
        canvas.drawText(valueText, cx, cy + 12f, hubValuePaint)
        canvas.drawText("cm", cx, cy + 30f, hubUnitPaint)
    }
}
