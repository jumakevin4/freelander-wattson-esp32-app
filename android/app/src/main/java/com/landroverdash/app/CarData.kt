package com.landroverdash.app

data class CarData(
    val speedKmh:    Float   = 0f,
    /** State of charge 0–100 %, derived in the app from pack voltage.
     *  -1 = not known yet (no usable voltage). The firmware's own "soc" field
     *  is always 0 and is not used. */
    val battSOC:     Int     = -1,
    val battVoltage: Float   = 0f,    // from Tesla OpenInverter CAN
    val battCurrent: Float   = 0f,    // from Tesla OpenInverter CAN
    val battTempC:   Float   = 0f,    // battery temperature °C (0 = not reported yet)
    val motorTempC:  Float   = 0f,    // motor temperature °C   (0 = not reported yet)
    /** 12 V rail (uaux from the inverter). 0 = not reported yet. */
    val auxVoltage:  Float   = 0f,
    /** openinverter lasterr code. -1 = not read yet, 0 = no fault. */
    val errCode:     Int     = -1,
    /** Decoded name for [errCode], e.g. "PRECHARGE". */
    val errName:     String  = "",
    /** openinverter status bitfield. -1 = not read yet. */
    val statusBits:  Int     = -1,
    /** Decoded status flags, e.g. "WaitStart|BrakeCheck", or "None". */
    val statusText:  String  = "",
    val brakeOn:     Boolean = false,
    val acOn:        Boolean = false,
    /** ESP32 uptime (ms) for buffered/synced records; 0 for live packets. */
    val tsMs:        Long    = 0L,
    /** True when this packet was replayed from the ESP32 sync buffer. */
    val isSynced:    Boolean = false,
    /**
     * Active drive mode read back from the inverter by the ESP32:
     * ECO / NORMAL / SPORT, or CUSTOM if fweak was tuned outside the presets.
     * Blank means not yet known — never assume NORMAL.
     */
    val driveMode:   String  = "",
    /**
     * Inverter has left Off, so [battVoltage] is the real pack voltage (v7.3+).
     * Before that this car's DC link is 0 or a capacitor bank bleeding down —
     * it precharges only on the start signal. Null = firmware doesn't say.
     */
    val dcLinkUp:    Boolean? = null
)
