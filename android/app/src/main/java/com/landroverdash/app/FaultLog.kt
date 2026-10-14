package com.landroverdash.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Every inverter error the car reports, kept on the phone (the newest 30), so
 * one that clears itself is still on record. The car interface writes them to
 * ERRORS.CSV as well, but only with an SD card fitted.
 *
 * Errors are logged here rather than shown on the Drive screen. The code is
 * the inverter's LAST error and stays set until it restarts, so a one-off
 * glitch would sit there in red for the rest of the drive. Only a temperature
 * fault the readings back up goes on Drive (see [LiveVehicleData.derate]).
 * Settings → Inverter shows this log.
 *
 * One event runs from when a code first appears until it goes back to 0 or
 * another code replaces it. Seen again within 10 minutes (after a reconnect,
 * say) it is the same event.
 */
object FaultLog {

    data class Event(
        val code: Int,
        val name: String,
        /** When the code first appeared, and when it was last seen still set. */
        val firstMs: Long,
        val lastMs: Long,
        /** Over: the code went back to 0, another replaced it, or it went quiet. */
        val ended: Boolean,
        /** When it was seen to end; 0 = not seen (the link was down). */
        val endMs: Long,
        /** Highest motor and inverter temperatures while it was set, °C. */
        val peakMotorC: Float,
        val peakInverterC: Float,
        /** The readings backed it up (a real derate), so it showed on Drive. */
        val confirmed: Boolean
    )

    private const val MAX_EVENTS = 30
    private const val SAME_EVENT_GAP_MS = 10 * 60_000L
    private const val SAVE_EVERY_MS = 30_000L

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    /** Newest first. */
    val events: StateFlow<List<Event>> = _events

    /** When this run of the app started: events since then are "this drive". */
    val sessionStartMs = System.currentTimeMillis()

    private var prefs: SharedPreferences? = null
    private var lastSaveMs = 0L

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("fault_log", Context.MODE_PRIVATE)
        prefs = p
        _events.value = runCatching { parse(p.getString("events", null)) }.getOrDefault(emptyList())
    }

    /**
     * A fresh live reading of the inverter's error code (0 = none) with the
     * temperatures at that moment, and whether the readings back it up.
     */
    @Synchronized
    fun onReading(code: Int, name: String, motorC: Float, inverterC: Float,
                  confirmed: Boolean, nowMs: Long) {
        if (code < 0) return
        val list = _events.value
        val open = list.firstOrNull()?.takeIf { !it.ended }
        val recent = open != null && nowMs - open.lastMs <= SAME_EVENT_GAP_MS
        when {
            code == 0 -> if (open != null)
                // Gone. If the link was down meanwhile, when it went is unknown.
                replaceFirst(list, open.copy(ended = true, endMs = if (recent) nowMs else 0L), save = true)

            open != null && open.code == code && recent ->
                replaceFirst(list, open.copy(
                    lastMs = nowMs,
                    peakMotorC = max(open.peakMotorC, motorC),
                    peakInverterC = max(open.peakInverterC, inverterC),
                    confirmed = open.confirmed || confirmed),
                    save = confirmed && !open.confirmed)

            else -> {
                // A new error, or the same one after a long silence.
                val closed = open?.copy(ended = true, endMs = if (recent) nowMs else 0L)
                val rest = if (closed != null) listOf(closed) + list.drop(1) else list
                val event = Event(code, name, nowMs, nowMs, false, 0L, motorC, inverterC, confirmed)
                set((listOf(event) + rest).take(MAX_EVENTS), save = true)
            }
        }
    }

    /** The link dropped: keep what we have. */
    @Synchronized
    fun flush() = save()

    @Synchronized
    fun clear() = set(emptyList(), save = true)

    private fun replaceFirst(list: List<Event>, e: Event, save: Boolean) =
        set(listOf(e) + list.drop(1), save)

    private fun set(list: List<Event>, save: Boolean) {
        _events.value = list
        val now = System.currentTimeMillis()
        if (save || now - lastSaveMs >= SAVE_EVERY_MS) save()
    }

    private fun save() {
        val p = prefs ?: return
        lastSaveMs = System.currentTimeMillis()
        val arr = JSONArray()
        for (e in _events.value) arr.put(JSONObject()
            .put("c", e.code).put("n", e.name).put("f", e.firstMs).put("l", e.lastMs)
            .put("x", e.ended).put("e", e.endMs)
            .put("pm", e.peakMotorC.toDouble()).put("pi", e.peakInverterC.toDouble())
            .put("ok", e.confirmed))
        p.edit().putString("events", arr.toString()).apply()
    }

    private fun parse(s: String?): List<Event> {
        if (s.isNullOrEmpty()) return emptyList()
        val arr = JSONArray(s)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Event(o.getInt("c"), o.optString("n"), o.getLong("f"), o.getLong("l"),
                o.optBoolean("x", true), o.optLong("e"),
                o.optDouble("pm", 0.0).toFloat(), o.optDouble("pi", 0.0).toFloat(),
                o.optBoolean("ok"))
        }
    }

    /** Plain words for the errors this car's readings can confirm. */
    fun describe(name: String): String? = when (Health.tempFaultPart(name)) {
        Health.Part.MOTOR -> "motor over-temperature"
        Health.Part.INVERTER -> "inverter over-temperature"
        null -> null
    }
}
