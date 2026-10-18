package com.landroverdash.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Below this the DC-DC isn't charging and the car is running off the 12 V
 * battery alone, which strands it however full the pack is.
 * Must match AUX_WARN_VOLTS in freelander-esp32.ino.
 */
internal const val AUX_WARN_VOLTS = 12.0f
/** Recovery threshold. Above this the low-rail latch re-arms. */
internal const val AUX_WARN_CLEAR_VOLTS = 12.5f

/**
 * One place that decides whether the car is healthy, so the Drive strip, the
 * Vehicle page and the rail badge never disagree.
 *
 * Derates are the OpenInverter settings on this car. The warn levels sit a
 * little below them so there is time to back off before power is cut. The
 * inverter figure is the internal IGBT temperature, which runs high and
 * spikes — tune INVERTER_WARN_C here if it warns too eagerly.
 *
 * Inverter error codes alone don't make the car unhealthy here: the code is
 * the inverter's last error and stays set until it restarts, so a one-off
 * glitch would stay red all drive. They go to [FaultLog] (Settings →
 * Inverter). The one exception is a temperature fault the readings back up —
 * a real derate — which is [State.derate] and the only FAULT level.
 */
object Health {
    const val MOTOR_DERATE_C    = 150f
    const val INVERTER_DERATE_C = 110f
    const val MOTOR_WARN_C      = 130f
    const val INVERTER_WARN_C   = 95f

    /**
     * How long the temperature must stay at its warning level or above before
     * a temperature fault counts as real. A sensor glitch is a spike; a real
     * overheat stays.
     */
    const val CONFIRM_MS = 10_000L

    enum class Level { OK, WARN, FAULT }

    enum class Part { MOTOR, INVERTER }

    /**
     * The part an inverter error is about when it's a temperature limit:
     * TMPMMAX is the motor, TMPHSMAX the inverter's heatsink. Null for any other.
     */
    fun tempFaultPart(name: String): Part? {
        val n = name.uppercase()
        return when {
            "TMPMMAX" in n -> Part.MOTOR
            "TMPHSMAX" in n -> Part.INVERTER
            else -> null
        }
    }

    data class State(
        val level: Level,
        /** Count of amber items (motor, inverter, 12 V). */
        val warnings: Int,
        val motorC: Float,
        val inverterC: Float,
        val motorWarn: Boolean,
        val inverterWarn: Boolean,
        val auxV: Float,
        val auxLow: Boolean,
        /** Inverter error code: -1 not read yet, 0 none. Logged, not shown, unless [derate]. */
        val errCode: Int,
        val errName: String,
        /** A temperature fault the readings back up: the part that's derating, or null. */
        val derate: Part? = null
    )

    fun evaluate(motorC: Float, inverterC: Float, auxV: Float, auxLow: Boolean,
                 errCode: Int, errName: String, derate: Part? = null): State {
        val motorWarn = motorC >= MOTOR_WARN_C
        val invWarn   = inverterC >= INVERTER_WARN_C
        val warnings  = listOf(motorWarn, invWarn, auxLow).count { it }
        val level = when {
            derate != null -> Level.FAULT
            warnings > 0   -> Level.WARN
            else           -> Level.OK
        }
        return State(level, warnings, motorC, inverterC, motorWarn, invWarn,
            auxV, auxLow, errCode, errName, derate)
    }

    private class Readings(val motor: Float, val inverter: Float, val aux: Float,
                           val low: Boolean, val err: Int)

    /** Live health, from the process-wide telemetry snapshot. */
    val state: Flow<State> = combine(
        LiveVehicleData.motorTempC,
        LiveVehicleData.battTempC,      // heatsink / IGBT: the "Inverter" reading
        LiveVehicleData.auxVoltageV,
        LiveVehicleData.auxLow,
        LiveVehicleData.errCode
    ) { motor, inverter, aux, low, err -> Readings(motor, inverter, aux, low, err) }
        .combine(LiveVehicleData.derate) { r, derate ->
            evaluate(r.motor, r.inverter, r.aux, r.low, r.err, LiveVehicleData.errName.value, derate)
        }

    /** 0–1 position of [tempC] against its derate point, for headroom bars. */
    fun headroom(tempC: Float, derateC: Float): Float =
        if (tempC <= 0f) 0f else (tempC / derateC).coerceIn(0f, 1f)
}
