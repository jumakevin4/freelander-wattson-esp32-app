package com.landroverdash.app

import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Energy — this trip's energy per bucket, plus efficiency and trip energy.
 *
 * Reads the process-wide [LiveTripData] rather than a snapshot passed in an
 * Intent, so it can be opened from the rail on any screen and keeps updating
 * while it is open. (The old page showed whatever the dashboard handed it and
 * had to be reopened for fresh data.)
 */
class EnergyChartActivity : WattsonActivity() {

    private lateinit var rowRolling: StatRow
    private lateinit var rowTrip: StatRow
    private lateinit var rowAverage: StatRow
    private lateinit var rowUsed: StatRow
    private lateinit var rowRecovered: StatRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_energy_chart)
        bindNavRail(Screen.ENERGY)

        val bars = findViewById<EnergyBarsView>(R.id.energyBars)
        val tvUnit = findViewById<TextView>(R.id.tvBucketUnit)
        val tvSubtitle = findViewById<TextView>(R.id.tvEnergySubtitle)

        rowRolling   = StatRow.of(this, R.id.rowRolling, "Last 30 min")
        rowTrip      = StatRow.of(this, R.id.rowTrip, "This trip")
        rowAverage   = StatRow.of(this, R.id.rowAverage, "Your average")
        rowUsed      = StatRow.of(this, R.id.rowUsed, "Used", "Net, measured")
        rowRecovered = StatRow.of(this, R.id.rowRecovered, "Recovered")
        rowRecovered.value.setTextColor(color(R.color.accent))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LiveTripData.energyBuckets.collect { list ->
                        bars.setBuckets(list)
                        bars.contentDescription = describe(list)
                    }
                }
                launch {
                    LiveTripData.bucketMinutes.collect { g -> tvUnit.text = unitLabel(g) }
                }
                launch {
                    LiveTripData.tripTimeMin.collect { m -> tvSubtitle.text = "This trip · ${duration(m)}" }
                }
                launch {
                    combine(LiveTripData.rollingEffWhPerKm, LiveTripData.tripEffWhPerKm,
                        LiveTripData.lifetimeEffWhPerKm) { r, t, l -> Triple(r, t, l) }
                        .collect { (r, t, l) -> renderEfficiency(r, t, l) }
                }
                launch {
                    combine(LiveVehicleData.usedKwh, LiveTripData.regenKwh) { u, g -> u to g }
                        .collect { (used, regen) -> renderEnergy(used, regen) }
                }
            }
        }
    }

    private fun kmPerKwh(whPerKm: Float) = if (whPerKm > 0f) 1000f / whPerKm else 0f

    private fun renderEfficiency(rollingWh: Float, tripWh: Float, lifetimeWh: Float) {
        val rolling = kmPerKwh(rollingWh)
        val trip = kmPerKwh(tripWh)
        val avg = kmPerKwh(lifetimeWh)

        rowRolling.value.text = Fmt.valueUnit(this, Fmt.one(rolling), if (rolling > 0f) "km/kWh" else "", unitSp = 14)
        // Unlike the trip and average figures this one doesn't subtract regen
        // (the range projection is deliberately conservative), so say so.
        rowRolling.sub(if (rolling > 0f) "Range uses this; regen not counted"
                       else "After a few minutes of driving", color(R.color.text3))

        rowTrip.value.text = Fmt.valueUnit(this, Fmt.one(trip), if (trip > 0f) "km/kWh" else "", unitSp = 14)
        val semibold: Typeface? = ResourcesCompat.getFont(this, R.font.archivo_semibold)
        val regular: Typeface? = ResourcesCompat.getFont(this, R.font.archivo_regular)
        when {
            trip <= 0f -> { rowTrip.sub("Measuring…", color(R.color.text3)); rowTrip.sub.typeface = regular }
            avg <= 0f -> { rowTrip.sub("Nothing to compare yet", color(R.color.text3)); rowTrip.sub.typeface = regular }
            else -> {
                val pct = (trip / avg - 1f) * 100f
                when {
                    abs(pct) < 1f -> {
                        rowTrip.sub("Same as your average", color(R.color.text2))
                        rowTrip.sub.typeface = regular
                    }
                    pct > 0f -> {
                        rowTrip.sub(String.format(Locale.US, "%.0f%% above average", pct), color(R.color.accent))
                        rowTrip.sub.typeface = semibold
                    }
                    else -> {
                        rowTrip.sub(String.format(Locale.US, "%.0f%% below average", -pct), color(R.color.text2))
                        rowTrip.sub.typeface = semibold
                    }
                }
            }
        }

        rowAverage.value.text = Fmt.valueUnit(this, Fmt.one(avg), if (avg > 0f) "km/kWh" else "", unitSp = 14)
        rowAverage.sub(if (avg > 0f) "All saved trips" else "No saved trips yet", color(R.color.text3))
    }

    private fun renderEnergy(usedKwh: Float, regenKwh: Float) {
        val known = usedKwh != 0f || regenKwh > 0f
        rowUsed.value.text = if (known)
            Fmt.valueUnit(this, minusSign(String.format(Locale.US, "%.1f", usedKwh)), "kWh", unitSp = 14)
        else Fmt.NONE
        rowRecovered.value.text = if (known)
            Fmt.valueUnit(this, String.format(Locale.US, "%.1f", regenKwh), "kWh", unitSp = 14)
        else Fmt.NONE
        // Gross draw = net + what regen put back.
        val draw = usedKwh + regenKwh
        rowRecovered.sub(
            if (draw > 0.05f && regenKwh > 0f)
                String.format(Locale.US, "By regen · %.0f%% of draw", regenKwh / draw * 100f)
            else "By regen")
    }

    private fun minusSign(s: String) = if (s.startsWith("-")) "−" + s.substring(1) else s

    private fun unitLabel(minutes: Int): String = when {
        minutes <= 1 -> "Wh per minute"
        minutes < 60 -> "Wh per $minutes minutes"
        minutes == 60 -> "Wh per hour"
        else -> "Wh per ${minutes / 60} hours"
    }

    private fun duration(mins: Int): String = when {
        mins < 1 -> "under a minute"
        mins < 60 -> "$mins min"
        else -> "${mins / 60} h ${mins % 60} min"
    }

    private fun describe(list: List<EnergyBucket>): String {
        if (list.isEmpty()) return "No energy data yet this trip"
        val peak = list.maxOf { it.wh }
        val regen = list.sumOf { it.regenWh.toDouble() }
        return String.format(Locale.US,
            "Energy per bucket this trip: %d buckets, peak %.0f Wh, %.0f Wh recovered by regen",
            list.size, peak, regen)
    }
}
