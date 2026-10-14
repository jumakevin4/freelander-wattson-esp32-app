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
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Every cell's voltage as a column, grouped by module, in the style of the
 * Energy chart: grey columns, the highest cell white, the lowest in the
 * accent colour, and the pack average as a dashed line. Under each module, its
 * number and temperature.
 *
 * The axis is zoomed to the cells (a few mV either side), not drawn from 0 V,
 * so a 10 mV difference is visible; the gridline labels give the scale.
 */
class CellBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var snap: BatterySnapshot? = null

    /** The voltage axis in use, V, held between readings (see [onDraw]). NaN until the first. */
    private var axisLo = Float.NaN
    private var axisHi = Float.NaN
    private var axisStep = 0.01f

    fun setSnapshot(s: BatterySnapshot?) {
        snap = s
        invalidate()
    }

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val text2 = ContextCompat.getColor(context, R.color.text2)
    private val text3 = ContextCompat.getColor(context, R.color.text3)
    private val textC = ContextCompat.getColor(context, R.color.text)
    private val used = ContextCompat.getColor(context, R.color.bar_used)
    private val high = ContextCompat.getColor(context, R.color.fill)
    private val missing = ContextCompat.getColor(context, R.color.tick)

    private val face: Typeface = if (isInEditMode) Typeface.DEFAULT
        else ResourcesCompat.getFont(context, R.font.archivo_regular) ?: Typeface.DEFAULT

    private val gridPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.line); strokeWidth = dp(1f) }
    private val basePaint = Paint().apply {
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
        if (raw <= 0f) return 0.001f
        val p = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val m = raw / p
        val n = when { m <= 1f -> 1f; m <= 2f -> 2f; m <= 2.5f -> 2.5f; m <= 5f -> 5f; else -> 10f }
        return n * p
    }

    override fun onDraw(canvas: Canvas) {
        val fm = labelPaint.fontMetrics
        val rowH = fm.descent - fm.ascent
        val right = width - dp(4f)
        val top = dp(10f)
        // Two label rows under the columns: module number, then its temperature.
        val bottom = height - (dp(10f) + rowH * 2 + dp(4f))

        val s = snap
        if (s == null || s.highestCell < 0) {
            axisLo = Float.NaN
            canvas.drawText("Waiting for battery data", width / 2f, height / 2f, emptyPaint)
            return
        }

        // Axis zoomed to the cells, with some room either side, on nice steps.
        // It holds while the cells move a little, so the scale doesn't jump with
        // every reading: it re-fits only when a cell comes within 2 mV of an edge,
        // or the spread shrinks to a fifth of the axis (after a pull, say).
        val lowV = s.lowestV
        val highV = s.highestV
        val span = max(highV - lowV, 0.010f)
        val holds = !axisLo.isNaN() && lowV - axisLo >= 0.002f && axisHi - highV >= 0.002f &&
            span * 5f >= axisHi - axisLo
        if (!holds) {
            val pad = max(0.008f, span * 0.35f)
            axisStep = niceStep((span + 2 * pad) / 4f)
            axisLo = floor((lowV - pad) / axisStep) * axisStep
            axisHi = ceil((highV + pad) / axisStep) * axisStep
        }
        val step = axisStep
        val lo = axisLo
        val hi = axisHi
        val decimals = if (step >= 0.0099f) 2 else 3
        // Room for the widest voltage label, at any font size.
        labelPaint.textAlign = Paint.Align.RIGHT
        val left = max(dp(44f), labelPaint.measureText(String.format(Locale.US, "%.${decimals}f", hi)) + dp(14f))
        if (right <= left || bottom <= top) return
        val scale = (bottom - top) / (hi - lo)
        fun y(v: Float) = bottom - (v - lo) * scale

        // Grid and voltage labels.
        labelPaint.color = text3
        val half = (fm.ascent + fm.descent) / 2f
        val lines = ((hi - lo) / step).roundToInt()
        for (k in 1..lines) {
            val v = lo + k * step
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, gridPaint)
            canvas.drawText(String.format(Locale.US, "%.${decimals}f", v), left - dp(10f), yy - half, labelPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, basePaint)
        canvas.drawText(String.format(Locale.US, "%.${decimals}f", lo), left - dp(10f), bottom - half, labelPaint)

        // Columns: one slot per cell, plus one empty slot between modules.
        val n = s.cellV.size
        val modules = s.modules
        val slot = (right - left) / (n + (modules - 1).coerceAtLeast(0))
        val bw = (slot * 0.66f).coerceIn(dp(2f), dp(12f))
        val rad = min(dp(2.5f), bw / 2f)
        fun cx(i: Int) = left + (i + s.moduleOf(i) + 0.5f) * slot
        for (i in 0 until n) {
            val v = s.cellV[i]
            val x = cx(i)
            if (v.isNaN()) {
                // No reading from this cell: a short stub, so the gap is visible.
                barPaint.color = missing
                canvas.drawRect(x - bw / 2, bottom - dp(4f), x + bw / 2, bottom, barPaint)
                continue
            }
            barPaint.color = when (i) {
                s.highestCell -> high
                s.lowestCell -> accent
                else -> used
            }
            val yy = min(y(v), bottom - dp(2f))
            canvas.drawRoundRect(x - bw / 2, yy, x + bw / 2, bottom, rad, rad, barPaint)
            canvas.drawRect(x - bw / 2, max(yy, bottom - rad), x + bw / 2, bottom, barPaint)
        }

        // Pack average.
        val avg = s.averageV
        if (!avg.isNaN()) {
            val yy = y(avg)
            avgPath.reset()
            avgPath.moveTo(left, yy)
            avgPath.lineTo(right, yy)
            canvas.drawPath(avgPath, avgPaint)
        }

        // Module number and temperature under each group.
        labelPaint.textAlign = Paint.Align.CENTER
        val row1 = bottom + dp(10f) - fm.ascent
        val row2 = row1 + rowH + dp(4f)
        for (m in 0 until modules) {
            val first = m * s.cellsPerModule
            val last = min(n - 1, first + s.cellsPerModule - 1)
            val x = (cx(first) + cx(last)) / 2f
            labelPaint.color = text3
            canvas.drawText("M${m + 1}", x, row1, labelPaint)
            val t = s.moduleTempC.getOrElse(m) { Float.NaN }
            labelPaint.color = if (m == s.hottestModule) textC else text2
            canvas.drawText(if (t.isNaN()) Fmt.NONE else "${t.roundToInt()}°", x, row2, labelPaint)
        }
    }
}
