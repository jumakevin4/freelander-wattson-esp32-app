package com.landroverdash.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * A plain rounded bar: battery %, and the headroom bars that show how close
 * the motor and inverter are to their derate temperatures.
 */
class MeterBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0–1. */
    var fraction: Float = 0f
        set(value) {
            field = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
            invalidate()
        }

    var trackColor: Int = ContextCompat.getColor(context, R.color.track)
        set(value) { field = value; invalidate() }

    var fillColor: Int = ContextCompat.getColor(context, R.color.fill)
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.MeterBar)
            trackColor = a.getColor(R.styleable.MeterBar_meterTrackColor, trackColor)
            fillColor  = a.getColor(R.styleable.MeterBar_meterFillColor, fillColor)
            fraction   = a.getFloat(R.styleable.MeterBar_meterFraction, 0f)
            a.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        paint.color = trackColor
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        if (fraction > 0f) {
            // Never narrower than it is tall, so a tiny value is still a dot.
            val fw = (w * fraction).coerceAtLeast(h).coerceAtMost(w)
            paint.color = fillColor
            canvas.drawRoundRect(0f, 0f, fw, h, r, r, paint)
        }
    }
}
