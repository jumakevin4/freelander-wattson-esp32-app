package com.landroverdash.app

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide snapshot of the newest vehicle telemetry, updated by
 * [DashboardViewModel] on every Bluetooth packet (~2 Hz).
 *
 * Exists so screens that don't own the Bluetooth connection — the Live
 * Vehicle Data panel on the details page — can show values that update in
 * real time instead of a snapshot frozen at the moment the page opened.
 *
 * Temps hold their last non-zero reading so a momentary 0 (inverter asleep
 * or a dropped SDO poll) doesn't blank the display.
 */
object LiveVehicleData {

    private val _voltageV = MutableStateFlow(0f)
    val voltageV: StateFlow<Float> = _voltageV

    private val _battTempC = MutableStateFlow(0f)   // heatsink / "Inverter Temp"
    val battTempC: StateFlow<Float> = _battTempC

    private val _motorTempC = MutableStateFlow(0f)
    val motorTempC: StateFlow<Float> = _motorTempC

    private val _peakCurrentA = MutableStateFlow(0f)   // session peak
    val peakCurrentA: StateFlow<Float> = _peakCurrentA

    /** 12 V rail (uaux). Holds its last non-zero reading, like the temps. */
    private val _auxVoltageV = MutableStateFlow(0f)
    val auxVoltageV: StateFlow<Float> = _auxVoltageV

    /**
     * True once the rail has been seen below the warning threshold this session,
     * cleared when it recovers past the re-arm level.
     *
     * The latch lives HERE rather than in the activity. As activity state it was
     * reset to false by every recreation (so the toast re-fired on rotation) and
     * survived a trip reset (so it never re-armed) — and because the activity's
     * collector is lifecycle-gated, a dip that happened and recovered while
     * Settings or Details was on top went unreported entirely. Latching at the
     * data layer means the dip is recorded whenever it happens; the UI only
     * decides when to show it.
     */
    private val _auxLow = MutableStateFlow(false)
    val auxLow: StateFlow<Boolean> = _auxLow

    /** Lowest 12 V seen this session — catches a dip you weren't looking at. */
    private val _auxMinV = MutableStateFlow(0f)
    val auxMinV: StateFlow<Float> = _auxMinV

    /**
     * Energy used this session, kWh. Integrated from measured volts × amps, so
     * unlike a SOC-derived figure it is a real measurement.
     */
    private val _usedKwh = MutableStateFlow(0f)
    val usedKwh: StateFlow<Float> = _usedKwh

    /** Pushed by the ViewModel, which owns the energy integration. */
    fun onEnergy(kwh: Float) { _usedKwh.value = kwh }

    /**
     * Live pack power, kW: volts × amps. Positive while drawing, negative while
     * regenerating. 0 when there is no live voltage.
     */
    private val _powerKw = MutableStateFlow(0f)
    val powerKw: StateFlow<Float> = _powerKw

    /**
     * Drive mode as the inverter reports it (ECO / NORMAL / SPORT / CUSTOM).
     * Blank means not known yet — never assume NORMAL. Not cleared with the
     * trip: it is the car's state, not the trip's.
     */
    private val _driveMode = MutableStateFlow("")
    val driveMode: StateFlow<String> = _driveMode

    /** A/C compressor running. */
    private val _acOn = MutableStateFlow(false)
    val acOn: StateFlow<Boolean> = _acOn

    /** Inverter fault: code, decoded name. -1 = not read yet, 0 = no fault. */
    private val _errCode = MutableStateFlow(-1)
    val errCode: StateFlow<Int> = _errCode

    private val _errName = MutableStateFlow("")
    val errName: StateFlow<String> = _errName

    /** Condition bitfield and its decoded flags — explains "it won't start". */
    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText

    /**
     * A temperature fault the readings back up — the only inverter error shown
     * on the Drive screen. The inverter reports TMPMMAX (motor) or TMPHSMAX
     * (its heatsink) AND that temperature has stayed at or above its warning
     * level for [Health.CONFIRM_MS]. Any other error, or one the temperatures
     * don't support (a sensor glitch), is only logged: see [FaultLog].
     */
    private val _derate = MutableStateFlow<Health.Part?>(null)
    val derate: StateFlow<Health.Part?> = _derate
    /** Since when each temperature has been at its warning level (elapsed ms); 0 = not. */
    private var motorHotSinceMs = 0L
    private var inverterHotSinceMs = 0L

    /** A LIVE packet (replayed history never comes here). */
    fun onSample(d: CarData) {
        if (d.statusText.isNotEmpty()) _statusText.value = d.statusText
        _voltageV.value = d.battVoltage
        _powerKw.value = if (d.battVoltage > 0f) d.battVoltage * d.battCurrent / 1000f else 0f
        _driveMode.value = d.driveMode
        _acOn.value = d.acOn
        if (d.battTempC  != 0f) _battTempC.value  = d.battTempC
        if (d.motorTempC != 0f) _motorTempC.value = d.motorTempC

        // How long each temperature has stayed high, to tell a real overheat
        // from a sensor spike.
        val tick = SystemClock.elapsedRealtime()
        motorHotSinceMs = when {
            _motorTempC.value < Health.MOTOR_WARN_C -> 0L
            motorHotSinceMs == 0L -> tick
            else -> motorHotSinceMs
        }
        inverterHotSinceMs = when {
            _battTempC.value < Health.INVERTER_WARN_C -> 0L
            inverterHotSinceMs == 0L -> tick
            else -> inverterHotSinceMs
        }
        if (d.errCode >= 0) {
            fun held(since: Long) = since > 0L && tick - since >= Health.CONFIRM_MS
            val derate = when (if (d.errCode > 0) Health.tempFaultPart(d.errName) else null) {
                Health.Part.MOTOR -> Health.Part.MOTOR.takeIf { held(motorHotSinceMs) }
                Health.Part.INVERTER -> Health.Part.INVERTER.takeIf { held(inverterHotSinceMs) }
                null -> null
            }
            // Name before code: collectors run as each value is set, and read
            // the name when the code changes.
            _errName.value = d.errName
            _derate.value = derate
            _errCode.value = d.errCode
            FaultLog.onReading(d.errCode, d.errName, _motorTempC.value, _battTempC.value,
                derate != null, System.currentTimeMillis())
        }
        if (d.battCurrent > _peakCurrentA.value) _peakCurrentA.value = d.battCurrent
        if (d.auxVoltage > 0f) {
            _auxVoltageV.value = d.auxVoltage
            if (_auxMinV.value == 0f || d.auxVoltage < _auxMinV.value)
                _auxMinV.value = d.auxVoltage
            // Hysteresis: warn at 12.0, re-arm at 12.5, so a reading sitting on
            // the threshold cannot chatter.
            if (!_auxLow.value && d.auxVoltage < AUX_WARN_VOLTS) _auxLow.value = true
            else if (_auxLow.value && d.auxVoltage > AUX_WARN_CLEAR_VOLTS) _auxLow.value = false
        }
    }

    /**
     * The link to the car dropped (or a new connection is starting): the live
     * readings are no longer live, so clear them rather than leave the last
     * values on screen looking current — a frozen "Drawing 60 kW", or a warm
     * inverter warning that hides the link status on the Drive screen.
     * Trip figures (peak current, lowest 12 V, energy used) stay; errors are in [FaultLog].
     */
    fun onLinkLost() {
        _voltageV.value    = 0f
        _powerKw.value     = 0f
        _battTempC.value   = 0f
        _motorTempC.value  = 0f
        _auxVoltageV.value = 0f
        _auxLow.value      = false
        _errName.value     = ""
        _derate.value      = null
        _errCode.value     = -1
        _statusText.value  = ""
        _driveMode.value   = ""
        _acOn.value        = false
        motorHotSinceMs    = 0L
        inverterHotSinceMs = 0L
        FaultLog.flush()
    }

    /**
     * The mode-change refusal last shown to the driver, by identity. The
     * refusal flow replays its latest value to every new collector, so Modes
     * and Drive both check this before showing one.
     */
    @Volatile var lastModeRefusalShown: String? = null

    /** Cleared with the trip. */
    fun reset() {
        _voltageV.value     = 0f
        _battTempC.value    = 0f
        _motorTempC.value   = 0f
        _peakCurrentA.value = 0f
        _auxVoltageV.value  = 0f
        _auxMinV.value      = 0f
        _auxLow.value       = false
        _errCode.value      = -1
        _errName.value      = ""
        _derate.value       = null
        motorHotSinceMs     = 0L
        inverterHotSinceMs  = 0L
        _statusText.value   = ""
        _usedKwh.value      = 0f
        _powerKw.value      = 0f
    }
}
