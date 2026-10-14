package com.landroverdash.app

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Vehicle — live readings from the inverter and both batteries, updating
 * with every Bluetooth packet, and the history of all saved trips. The
 * Traction battery tile opens Battery (every cell), and the header opens
 * Settings; neither has a rail item.
 */
class DetailsActivity : WattsonActivity() {

    private val viewModel: DetailsViewModel by viewModels()

    /** Handle on one included [R.layout.item_vehicle_tile]. */
    private inner class Tile(id: Int, icon: Int, label: String) {
        val root: View = findViewById(id)
        val label: TextView = root.findViewById(R.id.tvTileLabel)
        val value: TextView = root.findViewById(R.id.tvTileValue)
        val sub: TextView = root.findViewById(R.id.tvTileSub)
        val bar: MeterBar = root.findViewById(R.id.tileBar)
        val icon: ImageView = root.findViewById(R.id.ivTileIcon)
        private val chevron: ImageView = root.findViewById(R.id.ivTileChevron)

        init {
            this.icon.setImageResource(icon)
            this.label.text = label
        }

        /** Make the whole tile a button that opens [target], with a chevron to say so. */
        fun opens(target: Screen, action: String) {
            chevron.visibility = View.VISIBLE
            root.foreground = ContextCompat.getDrawable(this@DetailsActivity, R.drawable.fg_tile_ripple)
            root.setOnClickListener { navigateTo(target, Screen.VEHICLE) }
            ViewCompat.replaceAccessibilityAction(root,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, action, null)
        }

        /** Amber (or red) label, icon and value while the reading needs attention. */
        fun alert(colorRes: Int?) {
            val c = colorRes?.let { color(it) }
            label.setTextColor(c ?: color(R.color.text2))
            icon.imageTintList = ColorStateList.valueOf(c ?: color(R.color.text2))
            value.setTextColor(c ?: color(R.color.text))
            root.setBackgroundResource(when (colorRes) {
                R.color.warn -> R.drawable.bg_tile_warn
                R.color.crit -> R.drawable.bg_tile_crit
                else -> R.drawable.bg_tile
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        bindNavRail(Screen.VEHICLE)

        val hv    = Tile(R.id.tileHv, R.drawable.ic_battery, "Traction battery")
        val power = Tile(R.id.tilePower, R.drawable.ic_energy, "Power")
        val motor = Tile(R.id.tileMotor, R.drawable.ic_thermometer, "Motor")
        val inv   = Tile(R.id.tileInverter, R.drawable.ic_chip, "Inverter")
        val aux   = Tile(R.id.tileAux, R.drawable.ic_car_battery, "Auxiliary battery")
        val used  = Tile(R.id.tileUsed, R.drawable.ic_bars_chart, "Energy used")
        val peak  = Tile(R.id.tilePeak, R.drawable.ic_pulse, "Peak current")
        val cond  = Tile(R.id.tileConditions, R.drawable.ic_info, "Conditions")

        // The cell voltages and module temperatures live behind this tile
        // (Battery has no rail item).
        hv.opens(Screen.BATTERY, "Open the battery cells")

        motor.bar.visibility = View.VISIBLE
        inv.bar.visibility = View.VISIBLE
        motor.sub.text = "Derates at ${Health.MOTOR_DERATE_C.toInt()} °C"
        inv.sub.text = "Derates at ${Health.INVERTER_DERATE_C.toInt()} °C"
        used.sub.text = "This trip"
        peak.sub.text = "This trip"

        val chip = findViewById<View>(R.id.faultChip)
        val chipIcon = findViewById<ImageView>(R.id.ivFaultChip)
        val chipText = findViewById<TextView>(R.id.tvFaultChip)

        // Settings has no rail item; it opens from here.
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            navigateTo(Screen.SETTINGS, Screen.VEHICLE)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // HV battery: volts, and the current worked out from power.
                launch {
                    LiveVehicleData.voltageV.combine(LiveVehicleData.powerKw) { v, p -> v to p }
                        .collect { (v, p) ->
                            if (v <= 0f) {
                                hv.value.text = Fmt.NONE
                                hv.sub.text = "Waiting for data"
                                power.value.text = Fmt.NONE
                                power.sub.text = ""
                                return@collect
                            }
                            hv.value.text = Fmt.valueUnit(this@DetailsActivity, Fmt.whole(v), "V", unitSp = 16)
                            val amps = p * 1000f / v
                            hv.sub.text = when {
                                amps >= 0.5f -> String.format(Locale.US, "%.0f A from the pack", amps)
                                amps <= -0.5f -> String.format(Locale.US, "%.0f A into the pack", -amps)
                                else -> "No current"
                            }
                            val regen = p <= -0.3f
                            power.value.text = Fmt.valueUnit(this@DetailsActivity,
                                (if (p < 0f) "−" else "") + String.format(Locale.US, "%.1f", abs(p)),
                                "kW", unitSp = 16)
                            power.value.setTextColor(color(if (regen) R.color.accent else R.color.text))
                            power.sub.text = when {
                                regen -> "Regenerating"
                                p >= 0.3f -> "Drawing"
                                else -> "Idle"
                            }
                            power.sub.setTextColor(color(if (regen) R.color.accent else R.color.text3))
                        }
                }
                // Temperatures and 12 V, judged by the same rules as the Drive strip.
                launch {
                    Health.state.combine(LiveVehicleData.auxMinV) { h, min -> h to min }
                        .collect { (h, min) ->
                            temp(motor, h.motorC, Health.MOTOR_DERATE_C, h.motorWarn)
                            temp(inv, h.inverterC, Health.INVERTER_DERATE_C, h.inverterWarn)

                            aux.value.text = if (h.auxV > 0f)
                                Fmt.valueUnit(this@DetailsActivity, Fmt.one(h.auxV), "V", unitSp = 16,
                                    unitColor = if (h.auxLow) color(R.color.warn) else null)
                            else Fmt.NONE
                            aux.sub.text = when {
                                h.auxLow -> "DC-DC not charging"
                                min > 0f -> String.format(Locale.US, "Trip low %.1f V", min)
                                else -> "Waiting for data"
                            }
                            aux.sub.setTextColor(color(if (h.auxLow) R.color.warn_soft else R.color.text3))
                            aux.alert(if (h.auxLow) R.color.warn else null)
                        }
                }
                launch {
                    LiveVehicleData.usedKwh.collect { kwh ->
                        used.value.text = if (kwh != 0f) Fmt.valueUnit(this@DetailsActivity,
                            (if (kwh < 0f) "−" else "") + String.format(Locale.US, "%.1f", abs(kwh)),
                            "kWh", unitSp = 16) else Fmt.NONE
                    }
                }
                launch {
                    LiveVehicleData.peakCurrentA.collect { a ->
                        peak.value.text = if (a > 0f)
                            Fmt.valueUnit(this@DetailsActivity, Fmt.whole(a), "A", unitSp = 16) else Fmt.NONE
                    }
                }
                launch {
                    LiveVehicleData.statusText.collect { s -> renderConditions(cond, s) }
                }
                // Only a derate the readings back up shows here, beside Settings.
                // Any other inverter error is logged under Settings → Inverter:
                // the code stays set until the inverter restarts, long after a
                // glitch has passed.
                launch {
                    combine(LiveVehicleData.derate, LiveVehicleData.errCode) { d, code -> d to code }
                        .collect { (derate, code) ->
                            if (derate == null) {
                                chip.visibility = View.GONE
                                return@collect
                            }
                            val name = LiveVehicleData.errName.value
                            chip.visibility = View.VISIBLE
                            chipText.text = (if (derate == Health.Part.MOTOR) "Motor" else "Inverter") +
                                " derating: ${name.ifBlank { "code $code" }} ($code)"
                            chipText.setTextColor(color(R.color.crit))
                            chipIcon.imageTintList = ColorStateList.valueOf(color(R.color.crit))
                            chip.setBackgroundResource(R.drawable.bg_status_chip_crit)
                        }
                }
            }
        }

        // ── History of all saved trips ──────────────────────────────────────
        val hDist   = cell(R.id.histDist, "Distance")
        val hTime   = cell(R.id.histTime, "Drive time")
        val hEnergy = cell(R.id.histEnergy, "Energy")
        val hEff    = cell(R.id.histEff, "Efficiency")
        val hSpeed  = cell(R.id.histSpeed, "Avg speed")
        val tvHistory = findViewById<TextView>(R.id.tvHistoryTitle)

        findViewById<View>(R.id.btnDeleteAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset all history?")
                .setMessage("Clears every saved trip, for good. The trip in progress isn't affected.")
                .setPositiveButton("Reset") { _, _ -> viewModel.deleteAll() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.totalTrips.collect { n ->
                        tvHistory.text = when (n) {
                            0 -> "History · no saved trips"
                            1 -> "History · 1 trip"
                            else -> "History · $n trips"
                        }
                    }
                }
                launch {
                    viewModel.totalDistanceKm.collect {
                        hDist.text = Fmt.valueUnit(this@DetailsActivity, Fmt.grouped(it), "km")
                    }
                }
                launch {
                    viewModel.totalDurationMin.collect { mins ->
                        hTime.text = Fmt.hoursMinutes(this@DetailsActivity, mins)
                    }
                }
                launch {
                    viewModel.totalEnergyKwh.collect {
                        hEnergy.text = Fmt.valueUnit(this@DetailsActivity,
                            if (it >= 100f) Fmt.grouped(it) else String.format(Locale.US, "%.1f", it), "kWh")
                    }
                }
                launch {
                    // Stored as Wh/km, shown as km/kWh.
                    viewModel.avgEfficiency.collect {
                        hEff.text = if (it > 0f)
                            Fmt.valueUnit(this@DetailsActivity, Fmt.kmPerKwh(it), "km/kWh") else Fmt.NONE
                    }
                }
                launch {
                    viewModel.avgSpeed.collect {
                        hSpeed.text = if (it > 0f)
                            Fmt.valueUnit(this@DetailsActivity, Fmt.whole(it), "km/h") else Fmt.NONE
                    }
                }
            }
        }
    }

    private fun cell(id: Int, label: String): TextView {
        val root = findViewById<View>(id)
        root.findViewById<TextView>(R.id.tvCellLabel).text = label
        return root.findViewById(R.id.tvCellValue)
    }

    private fun temp(t: Tile, c: Float, derate: Float, warn: Boolean) {
        // 0 means "not reported yet"; anything else, below freezing too, is real.
        t.value.text = if (c != 0f) Fmt.valueUnit(this, Fmt.whole(c, known = true), "°C", unitSp = 16,
            unitColor = if (warn) color(R.color.warn) else null) else Fmt.NONE
        t.bar.fraction = Health.headroom(c, derate)
        t.bar.fillColor = color(if (warn) R.color.warn else R.color.text2)
        t.bar.trackColor = color(if (warn) R.color.warn_track else R.color.line)
        t.sub.setTextColor(color(if (warn) R.color.warn_soft else R.color.text3))
        t.alert(if (warn) R.color.warn else null)
    }

    /**
     * The inverter's condition flags, e.g. "WaitStart|BrakeCheck": the reasons
     * it won't drive. Plain words for the ones we know, the raw names below.
     */
    private fun renderConditions(t: Tile, raw: String) {
        val flags = raw.split('|', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        when {
            raw.isBlank() || raw.trim() == "?" -> {
                t.value.text = Fmt.NONE
                t.sub.text = "Waiting for the inverter"
                t.alert(null)
            }
            flags.isEmpty() || flags.all { it.equals("None", ignoreCase = true) } -> {
                t.value.text = "None"
                t.sub.text = "Nothing holding it back"
                t.sub.setTextColor(color(R.color.text3))
                t.alert(null)
            }
            else -> {
                val active = flags.filterNot { it.equals("None", ignoreCase = true) }
                t.value.text = if (active.size == 1) friendly(active[0]) else "${active.size} active"
                t.sub.text = active.joinToString(" · ")
                t.sub.setTextColor(color(R.color.warn_soft))
                t.alert(R.color.warn)
            }
        }
        t.root.contentDescription = "Conditions: ${t.value.text}. ${t.sub.text}"
    }

    private fun friendly(flag: String): String = when (flag.lowercase(Locale.US)) {
        "waitstart"     -> "Waiting for start"
        "brakecheck"    -> "Brake check"
        "potpressed"    -> "Throttle pressed"
        "udcbelowudcsw" -> "Pack below switch-on"
        "udclow"        -> "Pack voltage low"
        "udchigh"       -> "Pack voltage high"
        "udclim"        -> "Voltage limit"
        "emcystop"      -> "Emergency stop"
        "mprot"         -> "Motor protection"
        "tmphs"         -> "Inverter hot"
        "tmpm"          -> "Motor hot"
        else            -> flag
    }
}
