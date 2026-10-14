package com.landroverdash.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Efficiency coaching scale: a short line in km/kWh with a tick at your
 * all-trips average and a dot for this trip. The range adapts so both fit.
 *
 * Use wrap_content for the height: the labels are in sp, so the view measures
 * itself to fit them at the head unit's font size (the "g" in "avg" used to be
 * cut off at a fixed 40 dp).
 */
class EfficiencyScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var trip = 0f
    private var avg  = 0f

    /** Colour of the trip dot (accent when better than average). */
    var dotColor: Int = ContextCompat.getColor(context, R.color.accent)
        set(value) { field = value; invalidate() }

    /** Background the dot sits on, drawn as a ring so it reads as cut out. */
    var ringColor: Int = ContextCompat.getColor(context, R.color.surface)
        set(value) { field = value; invalidate() }

    fun setValues(tripKmPerKwh: Float, avgKmPerKwh: Float) {
        trip = if (tripKmPerKwh.isNaN()) 0f else tripKmPerKwh
        avg  = if (avgKmPerKwh.isNaN()) 0f else avgKmPerKwh
        invalidate()
    }

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line) }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text2) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, dm)
        typeface = if (isInEditMode) Typeface.DEFAULT
                   else ResourcesCompat.getFont(context, R.font.archivo_regular) ?: Typeface.DEFAULT
    }
    private val text2 = ContextCompat.getColor(context, R.color.text2)
    private val text3 = ContextCompat.getColor(context, R.color.text3)

    /** Top of the label line, below the track and the average tick. */
    private val labelTop get() = dp(24f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Room for a full label line, descenders included.
        val fm = labelPaint.fontMetrics
        val wanted = ceil(labelTop + fm.descent - fm.ascent).toInt() + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(wanted, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val pad = dp(10f)               // room for the dot at either end
        val cy = dp(10f)

        linePaint.strokeWidth = 0f
        canvas.drawRoundRect(0f, cy - dp(2f), w, cy + dp(2f), dp(2f), dp(2f), linePaint)

        val known = listOf(trip, avg).filter { it > 0f }
        if (known.isEmpty()) return

        var lo = floor(known.minOrNull()!! - 1.5f).coerceAtLeast(0f)
        var hi = ceil(known.maxOrNull()!! + 1.5f)
        if (hi - lo < 4f) { val extra = (4f - (hi - lo)) / 2f; lo = max(0f, lo - ceil(extra)); hi = lo + 4f }
        fun x(v: Float) = pad + (v - lo) / (hi - lo) * (w - 2 * pad)

        val baseline = labelTop - labelPaint.fontMetrics.ascent

        // Edge labels first, then the average label if it doesn't collide.
        labelPaint.color = text3
        labelPaint.textAlign = Paint.Align.LEFT
        val loText = String.format(Locale.US, "%.0f", lo)
        val hiText = String.format(Locale.US, "%.0f", hi)
        val loRight = labelPaint.measureText(loText)
        val hiLeft = w - labelPaint.measureText(hiText)

        var avgLeft = Float.NaN
        var avgRight = Float.NaN
        var avgText = ""
        if (avg > 0f) {
            val ax = x(avg)
            canvas.drawRoundRect(ax - dp(1f), 0f, ax + dp(1f), dp(20f), dp(1f), dp(1f), tickPaint)
            avgText = String.format(Locale.US, "avg %.1f", avg)
            val aw = labelPaint.measureText(avgText)
            avgLeft = (ax - aw / 2f).coerceIn(0f, w - aw)
            avgRight = avgLeft + aw
        }
        val gap = dp(8f)
        if (avgLeft.isNaN() || avgLeft > loRight + gap) canvas.drawText(loText, 0f, baseline, labelPaint)
        if (avgRight.isNaN() || avgRight < hiLeft - gap) {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(hiText, w, baseline, labelPaint)
        }
        if (!avgLeft.isNaN()) {
            labelPaint.color = text2
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(avgText, avgLeft, baseline, labelPaint)
        }

        if (trip > 0f) {
            val tx = min(max(x(trip), pad), w - pad)
            dotPaint.color = ringColor
            canvas.drawCircle(tx, cy, dp(12f), dotPaint)
            dotPaint.color = dotColor
            canvas.drawCircle(tx, cy, dp(8f), dotPaint)
        }
    }
}
