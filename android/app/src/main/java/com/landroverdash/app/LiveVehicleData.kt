package com.landroverdash.app

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

    /** Inverter fault: code, decoded name. -1 = not read yet, 0 = no fault. */
    private val _errCode = MutableStateFlow(-1)
    val errCode: StateFlow<Int> = _errCode

    private val _errName = MutableStateFlow("")
    val errName: StateFlow<String> = _errName

    /** Condition bitfield and its decoded flags — explains "it won't start". */
    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText

    /**
     * Highest fault code seen this session. A fault can clear before you get
     * to the screen, so the live value alone would hide it.
     */
    private val _errSeen = MutableStateFlow(0)
    val errSeen: StateFlow<Int> = _errSeen

    private val _errSeenName = MutableStateFlow("")
    val errSeenName: StateFlow<String> = _errSeenName

    fun onSample(d: CarData) {
        if (d.errCode >= 0) {
            _errCode.value = d.errCode
            _errName.value = d.errName
            if (d.errCode > 0 && d.errCode != _errSeen.value) {
                _errSeen.value     = d.errCode
                _errSeenName.value = d.errName
            }
        }
        if (d.statusText.isNotEmpty()) _statusText.value = d.statusText
        _voltageV.value = d.battVoltage
        if (d.battTempC  != 0f) _battTempC.value  = d.battTempC
        if (d.motorTempC != 0f) _motorTempC.value = d.motorTempC
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
        _statusText.value   = ""
        _errSeen.value      = 0
        _errSeenName.value  = ""
        _usedKwh.value      = 0f
    }
}
