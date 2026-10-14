package com.landroverdash.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
 * Trip elevation profile against distance. Stretches driven with net regen are
 * drawn over the line in the accent colour, so you can see where the
 * descents paid you back. Marks the high point and where you are now.
 */
class ElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<ElevationPoint> = emptyList()

    fun setPoints(list: List<ElevationPoint>) {
        points = list
        invalidate()
    }

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val fill   = ContextCompat.getColor(context, R.color.fill)
    private val bg     = ContextCompat.getColor(context, R.color.bg)
    private val text2  = ContextCompat.getColor(context, R.color.text2)
    private val text3  = ContextCompat.getColor(context, R.color.text3)
    private val textC  = ContextCompat.getColor(context, R.color.text)

    private val face: Typeface = if (isInEditMode) Typeface.DEFAULT
        else ResourcesCompat.getFont(context, R.font.archivo_regular) ?: Typeface.DEFAULT
    private val faceBold: Typeface = if (isInEditMode) Typeface.DEFAULT_BOLD
        else ResourcesCompat.getFont(context, R.font.archivo_semibold) ?: Typeface.DEFAULT_BOLD

    private val gridPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.line); strokeWidth = dp(1f) }
    private val basePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.line_strong); strokeWidth = dp(1f) }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill; style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val regenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(4.5f)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tick); style = Paint.Style.STROKE
        strokeWidth = dp(1.5f); pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(14f); typeface = face }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f); typeface = faceBold; color = textC; textAlign = Paint.Align.RIGHT }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(17f); typeface = face; color = text3; textAlign = Paint.Align.CENTER }

    private val area = Path()
    private val line = Path()
    private val regen = Path()
    private val dash = Path()

    private fun step(span: Float, maxLines: Int, options: FloatArray): Float =
        options.firstOrNull { span / it <= maxLines } ?: options.last()

    override fun onDraw(canvas: Canvas) {
        val left = dp(58f)
        val right = width - dp(16f)
        val top = dp(28f)
        val bottom = height - dp(34f)
        if (right <= left || bottom <= top) return

        if (points.size < 2) {
            canvas.drawText("No elevation data yet this trip", (left + right) / 2f,
                (top + bottom) / 2f, emptyPaint)
            return
        }

        val minA = points.minOf { it.altitudeM }
        val maxA = points.maxOf { it.altitudeM }
        var lo = floor((minA - 30f) / 100f) * 100f
        var hi = ceil((maxA + 30f) / 100f) * 100f
        if (hi - lo < 200f) hi = lo + 200f
        val yStep = step(hi - lo, 4, floatArrayOf(50f, 100f, 200f, 250f, 500f, 1000f))
        lo = floor(lo / yStep) * yStep
        hi = ceil(hi / yStep) * yStep

        val maxKm = max(points.last().distanceKm, 0.5f)
        fun x(km: Float) = left + km / maxKm * (right - left)
        fun y(a: Float) = top + (hi - a) / (hi - lo) * (bottom - top)

        // Grid + altitude labels.
        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.color = text3
        val half = (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
        var a = lo + yStep
        while (a <= hi + 0.1f) {
            val gy = y(a)
            canvas.drawLine(left, gy, right, gy, gridPaint)
            // No unit here: the numbers above the chart say metres, and
            // "2,400 m" doesn't fit the gutter.
            canvas.drawText(String.format(Locale.US, "%,.0f", a), left - dp(10f), gy - half, labelPaint)
            a += yStep
        }
        canvas.drawLine(left, bottom, right, bottom, basePaint)

        // Distance labels.
        val xStep = step(maxKm, 6, floatArrayOf(0.5f, 1f, 2f, 5f, 10f, 20f, 25f, 50f, 100f))
        val xBase = bottom + dp(12f) - labelPaint.fontMetrics.ascent
        var d = 0f
        while (d <= maxKm + 0.01f) {
            val last = d + xStep > maxKm + 0.01f
            labelPaint.textAlign = if (d == 0f) Paint.Align.LEFT else Paint.Align.CENTER
            val txt = (if (xStep < 1f) String.format(Locale.US, "%.1f", d)
                       else String.format(Locale.US, "%.0f", d)) + if (last && d > 0f) " km" else ""
            canvas.drawText(txt, x(d), xBase, labelPaint)
            d += xStep
        }

        // Area + line.
        area.reset(); line.reset(); regen.reset()
        points.forEachIndexed { i, p ->
            val px = x(p.distanceKm); val py = y(p.altitudeM)
            if (i == 0) { area.moveTo(px, bottom); area.lineTo(px, py); line.moveTo(px, py) }
            else { area.lineTo(px, py); line.lineTo(px, py) }
        }
        area.lineTo(x(points.last().distanceKm), bottom)
        area.close()
        areaPaint.shader = LinearGradient(0f, top, 0f, bottom,
            Color.argb(31, Color.red(fill), Color.green(fill), Color.blue(fill)),
            Color.argb(0, Color.red(fill), Color.green(fill), Color.blue(fill)),
            Shader.TileMode.CLAMP)
        canvas.drawPath(area, areaPaint)
        canvas.drawPath(line, linePaint)

        // Regen stretches: segment i-1 → i is regen when point i says so.
        var open = false
        for (i in 1 until points.size) {
            if (points[i].regen) {
                if (!open) regen.moveTo(x(points[i - 1].distanceKm), y(points[i - 1].altitudeM))
                regen.lineTo(x(points[i].distanceKm), y(points[i].altitudeM))
                open = true
            } else open = false
        }
        canvas.drawPath(regen, regenPaint)

        // High point.
        val peak = points.maxByOrNull { it.altitudeM }!!
        val pkx = x(peak.distanceKm); val pky = y(peak.altitudeM)
        if (peak !== points.last()) {
            markPaint.color = bg
            canvas.drawCircle(pkx, pky, dp(5f), markPaint)
            markPaint.style = Paint.Style.STROKE; markPaint.strokeWidth = dp(2f); markPaint.color = fill
            canvas.drawCircle(pkx, pky, dp(5f), markPaint)
            markPaint.style = Paint.Style.FILL
            labelPaint.color = text2
            labelPaint.textAlign = Paint.Align.CENTER
            val pt = String.format(Locale.US, "%,.0f m", peak.altitudeM)
            val half2 = labelPaint.measureText(pt) / 2f
            canvas.drawText(pt, pkx.coerceIn(left + half2, right - half2), pky - dp(12f), labelPaint)
        }

        // Now.
        val now = points.last()
        val nx = x(now.distanceKm); val ny = y(now.altitudeM)
        dash.reset(); dash.moveTo(nx, ny + dp(10f)); dash.lineTo(nx, bottom)
        canvas.drawPath(dash, dashPaint)
        markPaint.color = bg
        canvas.drawCircle(nx, ny, dp(12f), markPaint)
        markPaint.color = accent
        canvas.drawCircle(nx, ny, dp(8f), markPaint)
        // The label sits to the left of the dot, clear of the line underneath
        // it: above the highest point of the line across the label's width,
        // or below the lowest if there's no room above.
        val nowText = String.format(Locale.US, "Now · %,.0f m", now.altitudeM)
        val tx = nx - dp(14f)
        val tw = nowPaint.measureText(nowText)
        val inRange = points.withIndex().filter { (i, p) ->
            val px = x(p.distanceKm)
            val nextX = if (i + 1 < points.size) x(points[i + 1].distanceKm) else px
            val prevX = if (i > 0) x(points[i - 1].distanceKm) else px
            nextX >= tx - tw && prevX <= tx
        }.map { y(it.value.altitudeM) }
        val highest = minOf(inRange.minOrNull() ?: ny, ny)
        val lowest = maxOf(inRange.maxOrNull() ?: ny, ny)
        val ascent = -nowPaint.fontMetrics.ascent
        val above = min(ny - dp(22f), highest - dp(10f))
        val ty = if (above - ascent > top - dp(20f)) above else lowest + dp(10f) + ascent
        canvas.drawText(nowText, tx, ty, nowPaint)
    }
}
