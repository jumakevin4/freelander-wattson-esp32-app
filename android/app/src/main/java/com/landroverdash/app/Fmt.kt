package com.landroverdash.app

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale

/** Number formatting shared by every screen: big value, small grey unit. */
object Fmt {

    /** Shown for a reading that isn't known yet: an en dash, which reads
     *  better than "--" in the wide Archivo Expanded figures. */
    const val NONE = "–"

    private var unitFace: Typeface? = null

    private fun unitTypeface(ctx: Context): Typeface? =
        unitFace ?: runCatching { ResourcesCompat.getFont(ctx, R.font.archivo_medium) }
            .getOrNull().also { unitFace = it }

    private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    /**
     * "52.4 km" — the value keeps the view's own size and weight; the unit is
     * [unitSp], medium weight and grey (or [unitColor]).
     */
    fun valueUnit(
        ctx: Context, value: String, unit: String,
        unitSp: Int = 15, unitColor: Int? = null, gap: String = " "
    ): CharSequence {
        val sb = SpannableStringBuilder(value)
        // An unknown reading is just the dash; "— km/h" adds nothing.
        if (unit.isEmpty() || value == NONE) return sb
        appendUnit(ctx, sb, gap + unit, unitSp, unitColor)
        return sb
    }

    /** "1h 24m" with small h and m, matching [valueUnit]. */
    fun hoursMinutes(ctx: Context, mins: Int, unitSp: Int = 15, unitColor: Int? = null): CharSequence {
        val m = mins.coerceAtLeast(0)
        val sb = SpannableStringBuilder("${m / 60}")
        appendUnit(ctx, sb, "h ", unitSp, unitColor)
        sb.append("%02d".format(Locale.US, m % 60))
        appendUnit(ctx, sb, "m", unitSp, unitColor)
        return sb
    }

    private fun appendUnit(ctx: Context, sb: SpannableStringBuilder, text: String,
                           unitSp: Int, unitColor: Int?) {
        val start = sb.length
        sb.append(text)
        val end = sb.length
        sb.setSpan(AbsoluteSizeSpan(unitSp, true), start, end, EXCL)
        sb.setSpan(ForegroundColorSpan(unitColor ?: ContextCompat.getColor(ctx, R.color.text2)),
            start, end, EXCL)
        unitTypeface(ctx)?.let { sb.setSpan(TypefaceSpan(it), start, end, EXCL) }
    }

    /** One decimal, or [NONE] when not known. */
    fun one(v: Float, known: Boolean = v > 0f): String =
        if (known && !v.isNaN()) String.format(Locale.US, "%.1f", v) else NONE

    /** Whole number, or [NONE]. */
    fun whole(v: Float, known: Boolean = v > 0f): String =
        if (known && !v.isNaN()) String.format(Locale.US, "%.0f", v) else NONE

    /** Whole number with thousands separators: 1,912. */
    fun grouped(v: Float): String =
        if (v.isNaN()) NONE else String.format(Locale.US, "%,.0f", v)

    /** Signed whole number with a real minus sign: +542 / −425. */
    fun signed(v: Float): String = when {
        v.isNaN() -> NONE
        v < 0f    -> "−" + String.format(Locale.US, "%,.0f", -v)
        else      -> "+" + String.format(Locale.US, "%,.0f", v)
    }

    /** Stored as Wh/km, shown as km/kWh. 0 → [NONE]. */
    fun kmPerKwh(whPerKm: Float): String = one(if (whPerKm > 0f) 1000f / whPerKm else 0f)
}
