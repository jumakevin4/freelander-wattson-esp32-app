package com.landroverdash.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

/**
 * Read-only elevation profile (altitude vs trip distance) for the current trip.
 * Data is passed in as parallel float arrays so this screen stays decoupled from
 * the dashboard ViewModel. Back via the header button (the chart itself is
 * free for touch/scroll interaction).
 */
class ElevationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIST = "dist_km"
        const val EXTRA_ALT  = "alt_m"
        const val EXTRA_MIN  = "min_m"
        const val EXTRA_MAX  = "max_m"
    }

    private lateinit var chart: LineChart

    private val lineColour = Color.parseColor("#1A6B3A")
    private val fillColour  = Color.parseColor("#661A6B3A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_elevation)

        applyTheme()

        chart = findViewById(R.id.elevationChart)


        val dist = intent.getFloatArrayExtra(EXTRA_DIST)
        val alt  = intent.getFloatArrayExtra(EXTRA_ALT)
        val minM = intent.getFloatExtra(EXTRA_MIN, Float.NaN)
        val maxM = intent.getFloatExtra(EXTRA_MAX, Float.NaN)

        findViewById<TextView>(R.id.tvElevSummary).text =
            "↑ ${fmt(maxM)}   ·   ↓ ${fmt(minM)}"

        setupChart()

        if (dist == null || alt == null || dist.size < 2 || dist.size != alt.size) {
            // Chart-native note, same style as the Energy Use page
            chart.clear()
            chart.invalidate()
            return
        }
        drawProfile(dist, alt)
    }

    private fun fmt(m: Float): String = if (m.isNaN()) "-- m" else "%.0f m".format(m)

    private fun setupChart() {
        val textCol  = Color.WHITE
        val labelCol = Color.parseColor("#DDEEDF")

        chart.apply {
            description.isEnabled = false
            legend.isEnabled      = false
            // Touch stays ENABLED so long profiles can be pinch-zoomed/panned
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)
            setNoDataText("No elevation data yet this trip")
            setNoDataTextColor(labelCol)

            // Floating style, matching the Energy Use page: no axis/grid lines,
            // 18sp bold labels.
            xAxis.apply {
                position  = XAxis.XAxisPosition.BOTTOM
                setDrawAxisLine(false)
                setDrawGridLines(false)
                textColor = textCol
                textSize  = 18f
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "%.1f km".format(value)
                }
            }
            axisLeft.apply {
                setDrawAxisLine(false)
                setDrawGridLines(false)
                textColor = textCol
                textSize  = 18f
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "%.0f m".format(value)
                }
            }
            axisRight.isEnabled = false
            extraBottomOffset   = 8f
        }
    }

    private fun drawProfile(dist: FloatArray, alt: FloatArray) {
        val entries = dist.indices.map { Entry(dist[it], alt[it]) }
        val set = LineDataSet(entries, "Elevation").apply {
            color           = lineColour
            lineWidth        = 2.5f
            setDrawCircles(false)
            setDrawValues(false)
            mode             = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor        = fillColour
            fillAlpha        = 102
        }
        chart.data = LineData(set)
        chart.invalidate()
    }

    private fun applyTheme() {
        val rootBg   = Color.parseColor("#000000")
        // Same green as the Settings section headings.
        val titleCol = Color.parseColor("#7FC49A")
        val subCol   = Color.parseColor("#DDEEDF")
        findViewById<View>(R.id.elevRoot).setBackgroundColor(rootBg)
        findViewById<TextView>(R.id.tvElevChartTitle).setTextColor(titleCol)
        findViewById<TextView>(R.id.tvElevSummary).setTextColor(subCol)
    }
}
