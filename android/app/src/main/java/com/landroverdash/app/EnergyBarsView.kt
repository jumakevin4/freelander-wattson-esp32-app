package com.landroverdash.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Trip energy per bucket: energy drawn grows up in grey, energy recovered by
 * regen grows down in the accent colour, with the trip's average (net) as a
 * dashed line. The newest bucket is white.
 */
class EnergyBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var buckets: List<EnergyBucket> = emptyList()

    fun setBuckets(list: List<EnergyBucket>) {
        buckets = list
        invalidate()
    }

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val text2  = ContextCompat.getColor(context, R.color.text2)
    private val text3  = ContextCompat.getColor(context, R.color.text3)
    private val textC  = ContextCompat.getColor(context, R.color.text)
    private val used   = ContextCompat.getColor(context, R.color.bar_used)
    private val newest = ContextCompat.getColor(context, R.color.fill)

    private val face: Typeface = if (isInEditMode) Typeface.DEFAULT
        else ResourcesCompat.getFont(context, R.font.archivo_regular) ?: Typeface.DEFAULT

    private val gridPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.line); strokeWidth = dp(1f) }
    private val zeroPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.tick); strokeWidth = dp(1f) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text2
        alpha = 205
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f); typeface = face }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(17f); typeface = face; color = text3; textAlign = Paint.Align.CENTER }

    private val avgPath = Path()

    /** 1, 2, 2.5 or 5 × 10ⁿ at or above [raw]. */
    private fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val p = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val m = raw / p
        val n = when { m <= 1f -> 1f; m <= 2f -> 2f; m <= 2.5f -> 2.5f; m <= 5f -> 5f; else -> 10f }
        return n * p
    }

    override fun onDraw(canvas: Canvas) {
        val left = dp(48f)
        val right = width - dp(4f)
        val top = dp(10f)
        val bottom = height - dp(30f)
        if (right <= left || bottom <= top) return

        if (buckets.isEmpty()) {
            canvas.drawText("No energy data yet this trip", (left + right) / 2f,
                (top + bottom) / 2f, emptyPaint)
            return
        }

        val draws  = buckets.map { it.wh.coerceAtLeast(0f) }
        val regens = buckets.map { it.regenWh.coerceAtLeast(0f) }
        val maxDraw  = max(draws.maxOrNull() ?: 0f, 1f)
        val maxRegen = regens.maxOrNull() ?: 0f

        val step = niceStep(maxDraw / 3f)
        val up = ceil(maxDraw / step) * step
        // The regen side only goes as deep as it needs to, in quarter steps,
        // so a little regen doesn't take a whole step's worth of height.
        val quarter = step / 4f
        val down = if (maxRegen > 0f) max(ceil(maxRegen / quarter) * quarter, quarter) else 0f
        val scale = (bottom - top) / (up + down)
        val zeroY = top + up * scale

        // Grid and y labels.
        labelPaint.textAlign = Paint.Align.RIGHT
        val labelX = left - dp(10f)
        val half = (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
        var v = step
        while (v <= up + 0.01f) {
            val y = zeroY - v * scale
            canvas.drawLine(left, y, right, y, gridPaint)
            labelPaint.color = text3
            canvas.drawText(fmt(v), labelX, y - half, labelPaint)
            v += step
        }
        if (down > 0f) {
            val y = zeroY + down * scale
            canvas.drawLine(left, y, right, y, gridPaint)
            labelPaint.color = accent
            canvas.drawText("−" + fmt(down), labelX, y - half, labelPaint)
        }
        canvas.drawLine(left, zeroY, right, zeroY, zeroPaint)
        labelPaint.color = text2
        canvas.drawText("0", labelX, zeroY - half, labelPaint)

        // Bars.
        val n = buckets.size
        val slot = (right - left) / n
        val bw = (slot * 0.62f).coerceIn(dp(2f), dp(14f))
        val rad = min(dp(3f), bw / 2f)
        for (i in 0 until n) {
            val cx = left + (i + 0.5f) * slot
            val d = draws[i]
            if (d > 0f) {
                barPaint.color = if (i == n - 1) newest else used
                val y = zeroY - d * scale
                canvas.drawRoundRect(cx - bw / 2, y, cx + bw / 2, zeroY, rad, rad, barPaint)
                canvas.drawRect(cx - bw / 2, max(y, zeroY - rad), cx + bw / 2, zeroY, barPaint)
            }
            val r = regens[i]
            if (r > 0f) {
                barPaint.color = accent
                val y = zeroY + r * scale
                canvas.drawRoundRect(cx - bw / 2, zeroY, cx + bw / 2, y, rad, rad, barPaint)
                canvas.drawRect(cx - bw / 2, zeroY, cx + bw / 2, min(y, zeroY + rad), barPaint)
            }
        }

        // Average net energy per bucket.
        val avgNet = buckets.sumOf { (it.wh - it.regenWh).toDouble() }.toFloat() / n
        if (avgNet > 0f) {
            val y = zeroY - avgNet * scale
            avgPath.reset()
            avgPath.moveTo(left, y)
            avgPath.lineTo(right, y)
            canvas.drawPath(avgPath, avgPaint)
        }

        // Time labels: up to five, the last one reads "Now".
        val count = min(5, max(2, ((right - left) / dp(110f)).toInt()))
        val baseline = bottom + dp(10f) - labelPaint.fontMetrics.ascent
        val idx = (0 until count).map { k -> if (count == 1) 0 else (k * (n - 1).toFloat() / (count - 1)).toInt() }
            .distinct()
        idx.forEachIndexed { k, i ->
            val last = k == idx.lastIndex
            val cx = left + (i + 0.5f) * slot
            labelPaint.color = if (last) textC else text3
            labelPaint.textAlign = when {
                last -> Paint.Align.RIGHT
                k == 0 -> Paint.Align.LEFT
                else -> Paint.Align.CENTER
            }
            val x = when { last -> right; k == 0 -> left; else -> cx }
            canvas.drawText(if (last) "Now" else clock(buckets[i].timestampMs), x, baseline, labelPaint)
        }
    }

    private fun fmt(v: Float): String =
        if (v % 1f == 0f) String.format(Locale.US, "%,.0f", v) else String.format(Locale.US, "%.1f", v)

    private val cal = Calendar.getInstance()
    private fun clock(ms: Long): String {
        cal.timeInMillis = ms
        return String.format(Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
