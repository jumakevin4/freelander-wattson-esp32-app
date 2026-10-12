package com.landroverdash.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat

/**
 * Circular SOC gauge.
 * Stroke width is proportional to the view size so it looks
 * identical on any screen density (phone, ATOTO, etc.).
 */
class BatteryCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0–100, or -1 for "not known yet" (drawn as "--"). A real 0 % now shows
     *  as "0%" in red — it used to read "--", exactly when it mattered most. */
    var soc: Int = -1
        set(value) { field = value.coerceIn(-1, 100); invalidate() }

    // Stroke as a fraction of the view's half-width — same visual weight on every screen
    private val strokeFraction = 0.22f   // 22% of radius → adjust if needed

    private val startAngle = 110f   // 40° gap centred at the bottom
    private val maxSweep   = 320f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style    = Paint.Style.STROKE
        color    = Color.parseColor("#D8E8D8")
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style    = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val lrTypeface: Typeface? by lazy {
        ResourcesCompat.getFont(context, R.font.landroverweb_bold)
    }

    private val socTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color     = Color.parseColor("#FFFFFF")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color     = Color.parseColor("#DDEEDF")
        isFakeBoldText = true
    }

    // The remaining-kWh sub-value that used to sit under the percentage has been
    // removed. It was derived from SOC × configured capacity, which is a
    // nominal figure rather than a measurement, so it read as more authoritative
    // than it was. The ring now shows the percentage alone, properly centred.

    private val oval = RectF()

    private fun interpolateColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from)   + f * (Color.red(to)   - Color.red(from))).toInt(),
            (Color.green(from) + f * (Color.green(to) - Color.green(from))).toInt(),
            (Color.blue(from)  + f * (Color.blue(to)  - Color.blue(from))).toInt()
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = minOf(measuredWidth, measuredHeight)
        setMeasuredDimension(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        // Stroke width is proportional to the view size — same on every screen
        val sw = cx * strokeFraction
        trackPaint.strokeWidth = sw
        arcPaint.strokeWidth   = sw

        val radius = cx - sw / 2f - 4f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Background track
        canvas.drawArc(oval, startAngle, maxSweep, false, trackPaint)

        // SOC arc — only when known; unknown leaves just the track
        if (soc >= 0) {
            val sweep = maxSweep * soc / 100f
            arcPaint.color = if (soc > 30) {
                Color.parseColor("#1A6B3A")
            } else {
                interpolateColor(
                    Color.parseColor("#CC2200"),
                    Color.parseColor("#66BB44"),
                    soc / 30f
                )
            }
            canvas.drawArc(oval, startAngle, sweep.coerceAtLeast(2f), false, arcPaint)
        }

        // Centre text
        socTextPaint.typeface = lrTypeface ?: Typeface.DEFAULT_BOLD
        labelPaint.typeface   = lrTypeface ?: Typeface.DEFAULT_BOLD

        if (soc >= 0) {
            val numText = "$soc"
            val pctText = "%"

            socTextPaint.textSize = radius * 0.66f
            val numBounds = Rect()
            socTextPaint.getTextBounds(numText, 0, numText.length, numBounds)

            labelPaint.textSize = radius * 0.20f
            val pctBounds = Rect()
            labelPaint.getTextBounds(pctText, 0, pctText.length, pctBounds)

            // True vertical centre now that nothing sits beneath it — the old
            // offset existed only to make room for the kWh sub-value.
            val baseline = cy + numBounds.height() / 2f
            val numX     = cx - pctBounds.width() / 2f
            canvas.drawText(numText, numX, baseline, socTextPaint)
            canvas.drawText(pctText,
                numX + numBounds.width() / 2f + pctBounds.width() / 2f + 4f,
                baseline - numBounds.height() + pctBounds.height(),
                labelPaint)
        } else {
            socTextPaint.textSize = radius * 0.46f
            val bounds = Rect()
            socTextPaint.getTextBounds("--", 0, 2, bounds)
            canvas.drawText("--", cx, cy + bounds.height() / 2f, socTextPaint)
        }
    }
}
