package com.landroverdash.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.sqrt

/**
 * Live power band: regen grows left from zero in the accent colour, draw grows
 * right in white. Regen gets the left [regenShare] of the width.
 *
 * Square-root scale on both sides. Cruising is 10–15 kW against a 150 kW
 * drive unit, which on a linear bar barely moves; root scaling spreads the low
 * end out (12.6 kW fills about 29 % of the draw side) while 150 kW still
 * reaches the end. The uneven tick spacing shows the scale isn't linear.
 */
class PowerBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var maxDrawKw  = 150f
    var maxRegenKw = 50f
    var regenShare = 0.22f

    /** Positive = drawing, negative = regenerating. Animates to the new value. */
    var powerKw: Float = 0f
        set(value) {
            val v = if (value.isNaN()) 0f else value
            field = v
            animator?.cancel()
            animator = ValueAnimator.ofFloat(shownKw, v).apply {
                duration = 350
                interpolator = DecelerateInterpolator()
                addUpdateListener { shownKw = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    private var shownKw = 0f
    private var animator: ValueAnimator? = null

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val overhang = dp(6f)
    private val bandH    = dp(22f)
    private val tickGap  = dp(8f)
    private val tickLen  = dp(6f)
    private val labelGap = dp(3f)

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val text2  = ContextCompat.getColor(context, R.color.text2)
    private val text3  = ContextCompat.getColor(context, R.color.text3)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.band_track) }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(31, Color.red(accent), Color.green(accent), Color.blue(accent)) }
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.fill) }
    private val regenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text) }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tick)
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        typeface = if (isInEditMode) Typeface.DEFAULT
                   else ResourcesCompat.getFont(context, R.font.archivo_regular) ?: Typeface.DEFAULT
    }

    private val ticks = floatArrayOf(-50f, -25f, 0f, 10f, 25f, 50f, 100f, 150f)

    private val track = RectF()
    private val clip = Path()
    private val tmp = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = labelPaint.fontMetrics
        val desired = overhang + bandH + tickGap + tickLen + labelGap + (fm.descent - fm.ascent)
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired.toInt() + 1, heightMeasureSpec))
    }

    /** x for a power value, on the square-root scale. */
    private fun xFor(kw: Float, w: Float): Float {
        val zero = w * regenShare
        return if (kw >= 0f)
            zero + sqrt((kw / maxDrawKw).coerceIn(0f, 1f)) * (w - zero)
        else
            zero - sqrt((-kw / maxRegenKw).coerceIn(0f, 1f)) * zero
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val top = overhang
        val bottom = overhang + bandH
        val r = bandH / 2f
        val zero = w * regenShare

        track.set(0f, top, w, bottom)
        canvas.drawRoundRect(track, r, r, trackPaint)

        // Everything inside the band is clipped to its rounded outline.
        clip.reset()
        clip.addRoundRect(track, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(0f, top, zero, bottom, zonePaint)

        val kw = shownKw
        if (kw > 0.2f) {
            val end = xFor(kw, w)
            canvas.save()
            canvas.clipRect(zero, top, w, bottom)
            tmp.set(zero - r, top, end, bottom)
            canvas.drawRoundRect(tmp, r, r, drawPaint)
            canvas.restore()
        } else if (kw < -0.2f) {
            val start = xFor(kw, w)
            canvas.save()
            canvas.clipRect(0f, top, zero, bottom)
            tmp.set(start, top, zero + r, bottom)
            canvas.drawRoundRect(tmp, r, r, regenPaint)
            canvas.restore()
        }
        canvas.restore()

        // Zero marker, taller than the band.
        val zw = dp(1.5f)
        tmp.set(zero - zw, 0f, zero + zw, bottom + overhang)
        canvas.drawRoundRect(tmp, zw, zw, zeroPaint)

        // Ticks and labels.
        val tickTop = bottom + tickGap
        val baseline = tickTop + tickLen + labelGap - labelPaint.fontMetrics.ascent
        ticks.forEachIndexed { i, v ->
            val x = xFor(v, w).coerceIn(dp(0.5f), w - dp(0.5f))
            canvas.drawLine(x, tickTop, x, tickTop + tickLen, tickPaint)
            val label = when {
                i == ticks.lastIndex -> "${v.toInt()} kW"
                v < 0f -> "−${(-v).toInt()}"
                else -> "${v.toInt()}"
            }
            labelPaint.color = when {
                v < 0f  -> accent
                v == 0f -> text2
                else    -> text3
            }
            labelPaint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                ticks.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(label, x, baseline, labelPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
