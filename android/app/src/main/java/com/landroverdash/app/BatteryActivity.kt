package com.landroverdash.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Traction battery — the BMS view, opened from the Vehicle screen's Traction
 * battery tile: every cell's voltage, grouped by module with each
 * module's temperature, and the highest, lowest and spread of both.
 *
 * Reads [LiveBatteryData] once a second (the rate the ESP32 sends it) and
 * at once when a packet lands. Until the ESP32 sends BMS data the screen
 * shows a simulation built from the live pack voltage and current, marked
 * with an amber dot so it is never mistaken for the real pack.
 */
class BatteryActivity : WattsonActivity() {

    private lateinit var bars: CellBarsView
    private lateinit var tvSubtitle: TextView
    private lateinit var simDot: View
    private lateinit var rowCellHigh: StatRow
    private lateinit var rowCellLow: StatRow
    private lateinit var rowCellDelta: StatRow
    private lateinit var rowTempHigh: StatRow
    private lateinit var rowTempLow: StatRow
    private lateinit var rowTempDelta: StatRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery)
        bindNavRail(Screen.BATTERY)

        bars = findViewById(R.id.cellBars)
        tvSubtitle = findViewById(R.id.tvBatterySubtitle)
        simDot = findViewById(R.id.simDot)
        rowCellHigh = StatRow.of(this, R.id.rowCellHigh, "Highest cell")
        rowCellLow = StatRow.of(this, R.id.rowCellLow, "Lowest cell")
        rowCellDelta = StatRow.of(this, R.id.rowCellDelta, "Delta")
        rowTempHigh = StatRow.of(this, R.id.rowTempHigh, "Highest")
        rowTempLow = StatRow.of(this, R.id.rowTempLow, "Lowest")
        rowTempDelta = StatRow.of(this, R.id.rowTempDelta, "Difference")
        rowCellLow.value.setTextColor(color(R.color.accent))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (true) {
                        render(LiveBatteryData.current())
                        delay(1_000)
                    }
                }
                // A real packet shows at once rather than on the next tick.
                launch {
                    LiveBatteryData.real.collect { r ->
                        if (r != null) render(LiveBatteryData.current())
                    }
                }
            }
        }
    }

    private fun render(s: BatterySnapshot) {
        bars.setSnapshot(s)
        tvSubtitle.text = "Cell voltage, V · ${s.cellV.size} cells · ${s.modules} modules"
        simDot.visibility = if (s.simulated) View.VISIBLE else View.GONE

        // ── Cells ──
        rowCellHigh.value.text = volts(s.highestV)
        rowCellHigh.sub(cellName(s, s.highestCell), color(R.color.text3))
        rowCellHigh.value.setTextColor(color(
            if (s.highestV > CELL_HIGH_V) R.color.warn else R.color.text))

        rowCellLow.value.text = volts(s.lowestV)
        rowCellLow.sub(cellName(s, s.lowestCell), color(R.color.text3))
        rowCellLow.value.setTextColor(color(
            if (s.lowestV < CELL_LOW_V) R.color.warn else R.color.accent))

        val delta = s.deltaMv
        val deltaMv = if (delta.isNaN()) -1 else delta.roundToInt()
        rowCellDelta.value.text = if (deltaMv < 0) Fmt.NONE
            else Fmt.valueUnit(this, deltaMv.toString(), "mV", unitSp = 14)
        // Judged on the delta alone, as shown: up to 20 mV is balanced and
        // anything over is out of balance; far out, it needs balancing.
        val (verdict, tone) = when {
            deltaMv < 0 -> "" to R.color.text3
            deltaMv <= BALANCED_MV -> "Balanced" to R.color.text3
            deltaMv <= NEEDS_BALANCING_MV -> "Slightly out of balance" to R.color.text2
            else -> "Needs balancing" to R.color.warn
        }
        rowCellDelta.sub(verdict, color(tone))
        rowCellDelta.value.setTextColor(color(
            if (tone == R.color.warn) R.color.warn else R.color.text))

        // ── Module temperatures ──
        rowTempHigh.value.text = degrees(s.maxTempC)
        rowTempHigh.sub(moduleName(s.hottestModule), color(R.color.text3))
        rowTempHigh.value.setTextColor(color(when {
            s.maxTempC >= TEMP_CRIT_C -> R.color.crit
            s.maxTempC >= TEMP_WARN_C -> R.color.warn
            else -> R.color.text
        }))

        rowTempLow.value.text = degrees(s.minTempC)
        rowTempLow.sub(moduleName(s.coolestModule), color(R.color.text3))

        val spread = s.tempDeltaC
        rowTempDelta.value.text = degrees(spread)
        val (tVerdict, tTone) = when {
            spread.isNaN() -> "" to R.color.text3
            spread <= 5f -> "Even across ${s.modules} modules" to R.color.text3
            spread <= 10f -> "Uneven" to R.color.text2
            else -> "Check the cooling" to R.color.warn
        }
        rowTempDelta.sub(tVerdict, color(tTone))
        rowTempDelta.value.setTextColor(color(
            if (tTone == R.color.warn) R.color.warn else R.color.text))

        bars.contentDescription = if (s.highestCell < 0) "No cell readings" else
            "Cells from ${volts3(s.lowestV)} to ${volts3(s.highestV)} volts, " +
            "a difference of ${delta.roundToInt()} millivolts" +
            (if (s.simulated) ". Simulated, no BMS data yet." else ".")
    }

    private fun volts(v: Float): CharSequence =
        if (v.isNaN()) Fmt.NONE else Fmt.valueUnit(this, volts3(v), "V", unitSp = 14)

    private fun volts3(v: Float) = String.format(Locale.US, "%.3f", v)

    private fun degrees(c: Float): CharSequence =
        if (c.isNaN()) Fmt.NONE else Fmt.valueUnit(this, String.format(Locale.US, "%.1f", c), "°C", unitSp = 14)

    private fun cellName(s: BatterySnapshot, cell: Int) =
        if (cell < 0) "" else "Cell ${cell + 1} · module ${s.moduleOf(cell) + 1}"

    private fun moduleName(m: Int) = if (m < 0) "" else "Module ${m + 1}"

    private companion object {
        /** Cell delta, mV: up to this is balanced; above it, out of balance. */
        const val BALANCED_MV = 20
        /** Cell delta, mV, above which the pack needs balancing (amber). */
        const val NEEDS_BALANCING_MV = 50
        /** Warning levels for the readings. NMC: 4.20 V is full, 3.0 V is empty. */
        const val CELL_HIGH_V = 4.20f
        const val CELL_LOW_V = 3.00f
        const val TEMP_WARN_C = 45f
        const val TEMP_CRIT_C = 55f
    }
}
