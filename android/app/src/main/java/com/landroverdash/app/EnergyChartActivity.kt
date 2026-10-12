package com.landroverdash.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Calendar

/**
 * Full-screen trip energy chart (Wh per 5-minute bucket), opened by tapping
 * the Efficiency tile on the dashboard. Receives a snapshot of the buckets
 * via Intent extras — reopen for fresh data.
 */
class EnergyChartActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TS       = "bucket_ts"   // LongArray  — bucket start times (epoch ms)
        const val EXTRA_WH       = "bucket_wh"   // FloatArray — Wh per bucket
        const val EXTRA_GRAN_MIN = "bucket_min"  // Int — minutes per bucket (adaptive)
    }

    // Same palette as ElevationActivity so the two pages read as a pair.
    private val lineColour = Color.parseColor("#1A6B3A")

    /**
     * Fill graded by height, carrying over what the bar colours used to say:
     * calm green at low draw, amber through the middle, clay at the peaks.
     *
     * The gradient is drawn across the chart's plot area, and the left axis is
     * pinned to zero, so a band's colour tracks its Wh as a fraction of the
     * trip maximum — the same rule the bars used. Alpha climbs with the colour
     * so heavy stretches read as denser, not just differently coloured.
     */
    private fun energyFill() = GradientDrawable(
        GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(
            Color.parseColor("#2644BB66"),   // low  — calm green, nearly transparent
            Color.parseColor("#66FFC14D"),   // mid  — amber
            Color.parseColor("#B3E8926B")    // high — clay, most opaque
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_energy_chart)

        applyTheme()


        val ts = intent.getLongArrayExtra(EXTRA_TS) ?: LongArray(0)
        val wh = intent.getFloatArrayExtra(EXTRA_WH) ?: FloatArray(0)
        buildChart(ts, wh)
    }

    private fun buildChart(ts: LongArray, wh: FloatArray) {
        val chart = findViewById<LineChart>(R.id.energyBarChart)

        val textCol  = Color.WHITE
        val labelCol = Color.parseColor("#DDEEDF")

        chart.apply {
            description.isEnabled = false
            legend.isEnabled      = false
            // Touch enabled to match the elevation page — a long trip makes
            // many buckets, and pinch/pan is the only way to read them.
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setDrawBorders(false)
            setBackgroundColor(Color.TRANSPARENT)
            setNoDataText("No energy data yet this trip")
            setNoDataTextColor(labelCol)

            xAxis.apply {
                position    = XAxis.XAxisPosition.BOTTOM
                setDrawAxisLine(false)
                setDrawGridLines(false)
                granularity = 1f
                textColor   = textCol
                textSize    = 18f
                typeface    = Typeface.DEFAULT_BOLD
                setLabelRotationAngle(0f)
            }
            axisLeft.apply {
                setDrawAxisLine(false)
                setDrawGridLines(false)
                setDrawZeroLine(false)
                textColor   = textCol
                textSize    = 18f
                typeface    = Typeface.DEFAULT_BOLD
                axisMinimum = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getAxisLabel(value: Float, axis: AxisBase?): String =
                        "%,d Wh".format(value.toInt())
                }
            }
            axisRight.isEnabled = false
            extraBottomOffset   = 12f
        }

        if (wh.isEmpty()) { chart.clear(); chart.invalidate(); return }

        // Single line like the elevation profile, but with the fill graded by
        // height so heavy-draw stretches still stand out the way the bars did.
        val entries = wh.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet = LineDataSet(entries, "Energy").apply {
            color     = lineColour
            lineWidth = 2.5f
            setDrawCircles(false)
            setDrawValues(false)
            mode      = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            // fillDrawable overrides fillColor, and fillAlpha is ignored once
            // it is set — the alpha lives in the gradient's own colours.
            fillDrawable = energyFill()
            // Touch is on for pinch/pan, so suppress the tap crosshair —
            // there are no per-point values worth highlighting here.
            isHighlightEnabled = false
        }

        chart.xAxis.apply {
            labelCount = entries.size.coerceAtMost(6)
            valueFormatter = object : ValueFormatter() {
                override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                    val idx = value.toInt()
                    if (idx < 0 || idx >= ts.size) return ""
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = ts[idx]
                    return "%02d:%02d".format(
                        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                }
            }
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun applyTheme() {
        val rootBg   = Color.parseColor("#000000")
        // Same green as the Settings section headings.
        val titleCol = Color.parseColor("#7FC49A")
        val labelCol = Color.parseColor("#DDEEDF")

        findViewById<View>(R.id.energyRoot).setBackgroundColor(rootBg)
        findViewById<TextView>(R.id.tvEnergyTitle).setTextColor(titleCol)
    }
}
