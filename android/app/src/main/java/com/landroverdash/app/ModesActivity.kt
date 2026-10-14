package com.landroverdash.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Modes — ECO / NORMAL / SPORT. Moved off the dashboard so the tiles can be
 * big enough to hit on a moving car.
 *
 * Same safety rule as the old chips: the first tap arms a mode (amber), a
 * second tap within [ARM_TIMEOUT_MS] sends it. Tapping the active mode or
 * waiting cancels. The active tile is what the inverter REPORTS, read back
 * off the CAN bus by the ESP32, never what was last asked for.
 *
 * Doesn't own the link: commands go over the dashboard's socket through
 * [VehicleLink], and the mode comes from [LiveVehicleData].
 */
class ModesActivity : WattsonActivity() {

    private data class Tile(
        val mode: String, val level: Int, val root: View,
        val bars: List<View>, val name: TextView, val status: TextView
    )

    private lateinit var tiles: List<Tile>
    private lateinit var ivInfo: ImageView
    private lateinit var tvInfo: TextView

    /** Mode the inverter reports. Blank until known; can be "CUSTOM". */
    private var activeMode = ""
    /** First tap seen, waiting for the confirming one. */
    private var armedMode: String? = null
    /** Sent, waiting for the inverter to report it (or refuse). */
    private var pendingMode: String? = null
    /** Last mode sent from this screen, kept after the wait gives up so a
     *  late report still counts as confirmation. */
    private var lastRequested: String? = null
    private var connected = false

    /** What the line under the tiles says. */
    private sealed class Info {
        object Hint : Info()
        object NotConnected : Info()
        data class Switched(val mode: String) : Info()
        data class Refused(val reason: String) : Info()
        object NoReply : Info()
        /** Sent, but this firmware doesn't report the mode back. */
        object Unconfirmable : Info()
        object SendFailed : Info()
    }
    private var info: Info = Info.Hint

    private val handler = Handler(Looper.getMainLooper())
    private val disarm = Runnable { armedMode = null; render() }
    private val giveUp = Runnable {
        if (pendingMode != null) {
            pendingMode = null
            info = if (activeMode.isEmpty()) Info.Unconfirmable else Info.NoReply
            render()
        }
    }

    private val ARM_TIMEOUT_MS = 3_000L
    /** How long to wait for the inverter to report a requested mode. */
    private val CONFIRM_TIMEOUT_MS = 6_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modes)
        bindNavRail(Screen.MODES)

        tiles = listOf(
            tile("ECO", 1, R.id.tileEco),
            tile("NORMAL", 2, R.id.tileNormal),
            tile("SPORT", 3, R.id.tileSport)
        )
        ivInfo = findViewById(R.id.ivModeInfo)
        tvInfo = findViewById(R.id.tvModeInfo)
        tiles.forEach { t -> t.root.setOnClickListener { onTap(t.mode) } }

        observeLink(findViewById(R.id.linkDot), findViewById(R.id.tvLinkStatus)) { up ->
            connected = up
            if (!up) {
                armedMode = null
                pendingMode = null
                handler.removeCallbacks(disarm)
                handler.removeCallbacks(giveUp)
                if (info == Info.Hint) info = Info.NotConnected
            } else if (info == Info.NotConnected) {
                info = Info.Hint
            }
            render()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LiveVehicleData.driveMode.collect { mode -> onReportedMode(mode) }
                }
                launch {
                    // modeRefusals replays its latest refusal to every new
                    // collector, so a refusal from before this screen opened
                    // would pop up again. Only a refusal that arrives while a
                    // command from here is waiting is an answer to it.
                    VehicleLink.service?.modeRefusals?.collect { reason ->
                        if (lastRequested == null) return@collect
                        if (reason === LiveVehicleData.lastModeRefusalShown) return@collect
                        LiveVehicleData.lastModeRefusalShown = reason
                        pendingMode = null
                        lastRequested = null
                        handler.removeCallbacks(giveUp)
                        info = Info.Refused(reason)
                        render()
                    }
                }
            }
        }
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacks(disarm)
        handler.removeCallbacks(giveUp)
        super.onDestroy()
    }

    private fun tile(mode: String, level: Int, id: Int): Tile {
        val root = findViewById<View>(id)
        val t = Tile(mode, level, root,
            listOf(root.findViewById(R.id.modeBar1), root.findViewById(R.id.modeBar2),
                root.findViewById(R.id.modeBar3)),
            root.findViewById(R.id.tvModeTileName),
            root.findViewById(R.id.tvModeTileStatus))
        t.name.text = mode
        return t
    }

    private fun onTap(mode: String) {
        handler.removeCallbacks(disarm)
        if (mode.equals(activeMode, ignoreCase = true)) {   // already active
            armedMode = null
            render()
            return
        }
        if (!connected) {
            armedMode = null
            info = Info.NotConnected
            render()
            return
        }
        if (armedMode == mode) {                            // confirming tap
            armedMode = null
            send(mode)
        } else {                                            // first tap: arm and wait
            armedMode = mode
            handler.postDelayed(disarm, ARM_TIMEOUT_MS)
        }
        render()
    }

    private fun send(mode: String) {
        pendingMode = mode
        lastRequested = mode
        info = Info.Hint
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, CONFIRM_TIMEOUT_MS)
        lifecycleScope.launch {
            val ok = VehicleLink.service?.sendCommand(mode) == true
            if (!ok && pendingMode == mode) {
                pendingMode = null
                lastRequested = null
                handler.removeCallbacks(giveUp)
                info = Info.SendFailed
                render()
            }
        }
    }

    private fun onReportedMode(mode: String) {
        val m = mode.trim().uppercase(Locale.US)
        activeMode = m
        if (lastRequested != null && lastRequested == m) {
            // Confirmed, even if it took longer than the wait.
            pendingMode = null
            lastRequested = null
            handler.removeCallbacks(giveUp)
            info = Info.Switched(m)
        }
        if (armedMode == m) {
            armedMode = null
            handler.removeCallbacks(disarm)
        }
        render()
    }

    // ── Rendering ────────────────────────────────────────────────────────
    private fun render() {
        val accent = color(R.color.accent)
        val warn = color(R.color.warn)
        val off = color(R.color.mode_bar_off)
        val bold = ResourcesCompat.getFont(this, R.font.archivo_bold)
        val medium = ResourcesCompat.getFont(this, R.font.archivo_medium)

        for (t in tiles) {
            val active = t.mode == activeMode
            val armed = t.mode == armedMode
            val pending = t.mode == pendingMode
            t.root.setBackgroundResource(when {
                active -> R.drawable.bg_mode_tile_active
                armed || pending -> R.drawable.bg_mode_tile_armed
                else -> R.drawable.bg_mode_tile_idle
            })
            val fill = when {
                active -> accent
                armed || pending -> warn
                else -> color(R.color.text2)
            }
            t.bars.forEachIndexed { i, bar ->
                bar.backgroundTintList = ColorStateList.valueOf(if (i < t.level) fill else off)
            }
            t.name.setTextColor(when {
                active -> color(R.color.text)
                armed || pending -> warn
                else -> color(R.color.mode_name_idle)
            })
            val (status, statusColor) = when {
                active -> "Active" to accent
                pending -> "Switching…" to warn
                armed -> "Tap again to switch" to warn
                else -> "Tap to select" to color(R.color.text3)
            }
            t.status.text = status
            t.status.setTextColor(statusColor)
            t.status.typeface = if (active || armed || pending) bold else medium
            t.status.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (active) R.drawable.ic_check else 0, 0, 0, 0)
            t.status.compoundDrawableTintList = ColorStateList.valueOf(accent)
            t.root.isSelected = active
            t.root.contentDescription = "${t.mode} mode. $status"
        }
        renderInfo()
    }

    /**
     * What each mode does, in a line, shown once the inverter confirms a
     * switch. Edit the wording here.
     */
    private val modeSummaries = mapOf(
        "ECO"    to "Gentler acceleration that uses the least energy, for the most range.",
        "NORMAL" to "Balanced response for everyday driving, with typical range.",
        "SPORT"  to "Full power and quicker response. Uses energy faster, so less range."
    )

    /** "ECO · Gentler acceleration …" with the mode name picked out. */
    private fun summary(mode: String): CharSequence {
        val text = modeSummaries[mode] ?: return "Switched to $mode."
        val sb = android.text.SpannableStringBuilder(mode).append(" · ").append(text)
        val end = mode.length
        sb.setSpan(android.text.style.ForegroundColorSpan(color(R.color.text)), 0, end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ResourcesCompat.getFont(this, R.font.archivo_semibold)?.let {
            sb.setSpan(android.text.style.TypefaceSpan(it), 0, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun renderInfo() {
        val custom = activeMode.isNotEmpty() && tiles.none { it.mode == activeMode }
        val (text, icon, tint) = when (val i = info) {
            is Info.Refused -> Triple("The car refused the change: ${i.reason}",
                R.drawable.ic_warning, R.color.warn)
            Info.SendFailed -> Triple("Couldn't send the mode to the car. Check the connection and try again.",
                R.drawable.ic_warning, R.color.warn)
            Info.NoReply -> Triple("Not confirmed yet. The tile turns teal when the inverter reports the change; if it doesn't, try again.",
                R.drawable.ic_warning, R.color.warn)
            Info.Unconfirmable -> Triple("Sent. The car doesn't report its drive mode back, so the change can't be confirmed here.",
                R.drawable.ic_info, R.color.text2)
            Info.NotConnected -> Triple("Not connected to the car, so the mode can't be changed right now.",
                R.drawable.ic_info, R.color.text2)
            is Info.Switched -> Triple(summary(i.mode),
                R.drawable.ic_check, R.color.accent)
            Info.Hint -> when {
                custom -> Triple("The inverter is on a custom tune ($activeMode). Choose a mode to go back to a preset.",
                    R.drawable.ic_info, R.color.text2)
                activeMode.isEmpty() && connected -> Triple(
                    "Waiting for the inverter to report its mode. You can still choose one: tap it, then tap it again within 3 seconds.",
                    R.drawable.ic_info, R.color.text2)
                else -> Triple(
                    "Tap a mode, then tap it again within 3 seconds to switch. If the car refuses, for example while moving, the reason stays here.",
                    R.drawable.ic_info, R.color.text2)
            }
        }
        tvInfo.text = text
        tvInfo.setTextColor(color(if (tint == R.color.accent) R.color.text2 else tint))
        ivInfo.setImageResource(icon)
        ivInfo.imageTintList = ColorStateList.valueOf(color(tint))
    }
}
