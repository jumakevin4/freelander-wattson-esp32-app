package com.landroverdash.app

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Below this the DC-DC isn't charging and the car is running off the 12 V
 * battery alone — which strands it regardless of how full the pack is.
 * Must match AUX_WARN_VOLTS in can_sniffer.ino.
 */
internal const val AUX_WARN_VOLTS = 12.0f
/** Recovery threshold. Above this the low-rail latch re-arms. */
internal const val AUX_WARN_CLEAR_VOLTS = 12.5f
internal val AUX_WARN_COLOR = Color.parseColor("#C4553D")   // clay
// White, matching valueCol in applyTheme. This was #1A4A2A, which is the old
// light-theme value colour and is all but invisible on a black background.
internal val AUX_OK_COLOR   = Color.parseColor("#FFFFFF")

/** "12.4 km" with the unit at half size. */
internal fun unitText(value: String, unit: String, spaced: Boolean = true): CharSequence {
    val s = if (spaced) "$value $unit" else "$value$unit"
    return SpannableStringBuilder(s).apply {
        setSpan(RelativeSizeSpan(0.5f), value.length, s.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val RESULT_RESET_TRIP = 1
    }

    private val viewModel: DetailsViewModel by viewModels()
    
    private lateinit var lttvDist:    TextView
    private lateinit var lttvTime:    TextView
    private lateinit var lttvEnergy:  TextView
    private lateinit var lttvEff:     TextView
    private lateinit var lttvAvgSpeed: TextView

    private lateinit var allLabels: List<TextView>
    private lateinit var allValues: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)


        lttvDist     = findViewById(R.id.lttvDist)
        lttvTime     = findViewById(R.id.lttvTime)
        lttvEnergy   = findViewById(R.id.lttvEnergy)
        lttvEff      = findViewById(R.id.lttvEff)
        lttvAvgSpeed = findViewById(R.id.lttvAvgSpeed)

        // Live vehicle data — observed from LiveVehicleData so these update
        // with every Bluetooth packet while this page is open.
        val tvVolt      = findViewById<TextView>(R.id.tvVolt)
        val tvBattTemp  = findViewById<TextView>(R.id.tvBattTemp)
        val tvMotorTemp = findViewById<TextView>(R.id.tvMotorTemp)
        val tvPeakCurr  = findViewById<TextView>(R.id.tvPeakCurr)
        val tvAux       = findViewById<TextView>(R.id.tvAux)
        val tvUsed      = findViewById<TextView>(R.id.tvUsedEnergy)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LiveVehicleData.voltageV.collect {
                        tvVolt.text = unitText(if (it != 0f) "%.0f".format(it) else "--", "V")
                    }
                }
                launch {
                    LiveVehicleData.battTempC.collect {
                        tvBattTemp.text = unitText(if (it != 0f) "%.0f".format(it) else "--", "°C")
                    }
                }
                launch {
                    LiveVehicleData.motorTempC.collect {
                        tvMotorTemp.text = unitText(if (it != 0f) "%.0f".format(it) else "--", "°C")
                    }
                }
                launch {
                    LiveVehicleData.peakCurrentA.collect {
                        tvPeakCurr.text = unitText(if (it != 0f) "%.0f".format(it) else "--", "A")
                    }
                }
                // Energy used this session, integrated from measured volts x amps.
                // One decimal: a trip is single-digit kWh, so whole numbers would
                // be too coarse to show progress.
                launch {
                    LiveVehicleData.usedKwh.collect {
                        tvUsed.text = unitText(if (it > 0f) "%.1f".format(it) else "--", "kWh")
                    }
                }
                // 12 V rail. Shown to one decimal — the difference between a
                // charging DC-DC and a flat battery is under two volts, so
                // whole numbers would hide it. Red below the warn threshold.
                launch {
                    LiveVehicleData.auxVoltageV.collect {
                        tvAux.text = unitText(if (it != 0f) "%.1f".format(it) else "--", "V")
                        tvAux.setTextColor(
                            if (it != 0f && it < AUX_WARN_VOLTS) AUX_WARN_COLOR else AUX_OK_COLOR)
                    }
                }
            }
        }

        allLabels = listOf(
            findViewById(R.id.ltlblDist),  findViewById(R.id.ltlblTime),
            findViewById(R.id.ltlblEnergy),
            findViewById(R.id.ltlblEff),   findViewById(R.id.ltlblAvgSpeed),
            findViewById(R.id.lblVolt),    findViewById(R.id.lblBattTemp),
            findViewById(R.id.lblMotorTemp), findViewById(R.id.lblPeakCurr),
            findViewById(R.id.lblAux),       findViewById(R.id.lblUsedEnergy)
        )
        allValues = listOf(
            lttvDist, lttvTime, lttvEnergy, lttvEff, lttvAvgSpeed,
            tvVolt, tvBattTemp, tvMotorTemp, tvPeakCurr, tvAux, tvUsed
        )

        applyTheme()

        // Back via the header button — the panels are no longer tap-to-close so
        // the live values can be watched without a stray touch dismissing them.

        // Delete all history with confirmation
        findViewById<TextView>(R.id.btnDeleteAll).expandTouchArea(12)
        findViewById<TextView>(R.id.btnDeleteAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete All History")
                .setMessage("Permanently delete all recorded trip data?")
                .setPositiveButton("Delete") { _, _ -> viewModel.deleteAll() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Long-term data from Room. Gated to STARTED like the live panel above —
        // these only paint views, so there is nothing to gain from collecting
        // while the screen is stopped.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.totalDistanceKm.collect {
                        lttvDist.text = unitText("%.0f".format(it), "km")
                    }
                }
                launch {
                    viewModel.totalDurationMin.collect { mins ->
                        lttvTime.text = "${mins / 60}h %02dm".format(mins % 60)
                    }
                }
                launch {
                    viewModel.totalEnergyKwh.collect {
                        lttvEnergy.text = unitText("%.1f".format(it), "kWh")
                    }
                }
                launch {
                    // Stored as Wh/km, shown as km/kWh (= 1000 / Wh/km)
                    viewModel.avgEfficiency.collect {
                        lttvEff.text = unitText(
                            if (it > 0f) "%.1f".format(1000f / it) else "--", "km/kWh")
                    }
                }
                launch {
                    viewModel.avgSpeed.collect {
                        lttvAvgSpeed.text =
                            unitText(if (it > 0f) "%.0f".format(it) else "--", "km/h")
                    }
                }
            }
        }
    }

    private fun applyTheme() {
        val rootBg   = Color.parseColor("#000000")
        val tileBg   = R.drawable.tile_background_dark
        // Panel titles and field labels: a lighter green than the Settings
        // section headings use, chosen for legibility on pure black.
        val titleCol = Color.parseColor("#7FC49A")
        // Field labels share the title green, one shade for all headings.
        val labelCol = Color.parseColor("#7FC49A")
        val valueCol = Color.parseColor("#FFFFFF")

        findViewById<View>(R.id.detailsRoot).setBackgroundColor(rootBg)
        findViewById<LinearLayout>(R.id.panelLongTerm).setBackgroundResource(tileBg)
        findViewById<LinearLayout>(R.id.panelCurrent).setBackgroundResource(tileBg)
        findViewById<TextView>(R.id.tvHistoryTitle).setTextColor(titleCol)
        findViewById<TextView>(R.id.tvCurrentTitle).setTextColor(titleCol)
        allLabels.forEach { it.setTextColor(labelCol) }
        allValues.forEach { it.setTextColor(valueCol) }
        // btnDeleteAll keeps its own red; it now sits inside the Long Term tile
        // so it needs no background of its own.
    }
}
