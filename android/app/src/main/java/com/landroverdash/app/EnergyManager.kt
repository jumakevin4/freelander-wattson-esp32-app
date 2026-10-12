package com.landroverdash.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-lifetime scope for fire-and-forget persistence.
 *
 * These writes used to run on viewModelScope, which ViewModel.clear() cancels
 * BEFORE onCleared() runs — so a ten-minute or hourly block that happened to
 * flush near teardown was silently dropped, as was the final trip save. A
 * SupervisorJob keeps one failed write from cancelling the rest.
 *
 * Deliberately not cancelled anywhere: it exists exactly so that work outlives
 * the screen that started it, and it only ever runs short database writes.
 */
internal val PersistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Manages energy accounting:
 *
 * On every BT sample:
 *   - Receives voltage (V), current (A) and the elapsed time (dtMs) since the
 *     previous sample
 *   - Calculates Wh = V × A × (dtMs / 3_600_000)  — integrated over real time,
 *     so the result is independent of the packet cadence (currently ~2 Hz)
 *   - Accumulates in memory
 *
 * Every 10 minutes:
 *   - Saves a EnergyTenMin record to Room
 *   - Resets the 10-min accumulator
 *
 * Every 60 minutes:
 *   - Aggregates the 6 ten-min blocks → EnergyHourly record
 *   - Deletes the 6 ten-min blocks for that hour
 *   - Resets the hourly accumulator
 *
 * Remaining capacity:
 *   remainingKwh = (soc/100) × designCapacity × SOH
 *   (consumed Wh is tracked separately as sessionWh)
 */
class EnergyManager(
    private val context: Context,
    private val dao: EnergyDao,
    /** Persistence scope. Must outlive the ViewModel — see [PersistScope]. */
    private val scope: CoroutineScope = PersistScope
) {
    private val TEN_MIN_MS = 10 * 60 * 1000L
    private val ONE_HOUR_MS = 60 * 60 * 1000L

    // Per-second samples (in memory)
    private var secondWh    = 0f
    private var secondCount = 0

    // 10-min block accumulator
    private var tenMinWh        = 0f
    private var tenMinDistKm    = 0f
    private var tenMinSpeedSum  = 0f
    private var tenMinSamples   = 0
    private var tenMinStartMs   = 0L

    // Hourly accumulator (counts ten-min blocks saved in this hour)
    private var hourlyWh        = 0f
    private var hourlyDistKm    = 0f
    private var hourlySpeedSum  = 0f
    private var hourlyBlocks    = 0
    private var hourlyStartMs   = 0L

    // Session totals (reset on trip reset)
    var sessionWh    = 0f
        private set
    var sessionDistKm = 0f

    // Instantaneous values for dashboard display
    var latestVoltage = 0f
        private set
    var latestCurrent = 0f
        private set

    fun onSample(
        voltageV: Float,
        currentA: Float,
        speedKmh: Float,
        distDeltaKm: Float,
        dtMs: Long
    ) {
        latestVoltage = voltageV
        latestCurrent = currentA

        // Energy over the actual elapsed interval (Wh = W × hours).
        val wh = voltageV * currentA * (dtMs / 3_600_000f)
        secondWh    += wh
        secondCount++

        // Session totals
        sessionWh      += wh
        sessionDistKm  += distDeltaKm

        // 10-min accumulator
        val now = System.currentTimeMillis()
        if (tenMinStartMs == 0L) tenMinStartMs = now
        tenMinWh       += wh
        tenMinDistKm   += distDeltaKm
        tenMinSpeedSum += speedKmh
        tenMinSamples++

        // Hourly accumulator start
        if (hourlyStartMs == 0L) hourlyStartMs = now

        // Check 10-min boundary
        if ((now - tenMinStartMs) >= TEN_MIN_MS) flushTenMin(now)
    }

    private fun flushTenMin(now: Long) {
        val avgSpeed = if (tenMinSamples > 0) tenMinSpeedSum / tenMinSamples else 0f
        val block = EnergyTenMin(
            timestamp   = tenMinStartMs,
            totalWh     = tenMinWh,
            distanceKm  = tenMinDistKm,
            avgSpeedKmh = avgSpeed
        )
        scope.launch { dao.insertTenMin(block) }

        // Hourly accumulator
        hourlyWh       += tenMinWh
        hourlyDistKm   += tenMinDistKm
        hourlySpeedSum += avgSpeed
        hourlyBlocks++

        // Reset 10-min
        tenMinWh       = 0f
        tenMinDistKm   = 0f
        tenMinSpeedSum = 0f
        tenMinSamples  = 0
        tenMinStartMs  = now

        // Check hourly boundary (6 ten-min blocks = 1 hour)
        if (hourlyBlocks >= 6 || (now - hourlyStartMs) >= ONE_HOUR_MS) flushHourly(now)
    }

    private fun flushHourly(now: Long) {
        val avgSpeed = if (hourlyBlocks > 0) hourlySpeedSum / hourlyBlocks else 0f
        val efficiency = if (hourlyDistKm > 0f) hourlyWh / hourlyDistKm else 0f
        val record = EnergyHourly(
            timestamp         = hourlyStartMs,
            totalWh           = hourlyWh,
            distanceKm        = hourlyDistKm,
            efficiencyWhPerKm = efficiency,
            avgSpeedKmh       = avgSpeed
        )
        // Capture the boundary before the fields are reset below — the coroutine
        // runs later, by which time hourlyStartMs has moved on.
        val hourStart = hourlyStartMs
        scope.launch {
            dao.insertHourly(record)
            // Only the blocks that were rolled INTO this hourly row. This was
            // deleteTenMinBefore(now), which took out every ten-min row in the
            // table regardless of which hour it belonged to — so any history not
            // yet aggregated was destroyed the first time an hour rolled over.
            dao.deleteTenMinBetween(hourStart, now)
        }

        // Reset hourly
        hourlyWh       = 0f
        hourlyDistKm   = 0f
        hourlySpeedSum = 0f
        hourlyBlocks   = 0
        hourlyStartMs  = now
    }

    /** Remaining capacity in kWh based on SOC and battery settings */
    fun remainingKwh(socPct: Int): Float {
        val design = SettingsActivity.designCapacity(context)
        val soh    = SettingsActivity.soh(context)
        return (socPct / 100f) * design * soh
    }

    /**
     * Clear the session AND the block accumulators.
     *
     * The 10-minute and hourly buckets used to survive this, so the next block
     * boundary wrote a Room row containing energy, distance and speed samples
     * belonging to the trip the user had just deleted — and that then rolled up
     * into the hourly row.
     */
    fun resetSession() {
        secondWh       = 0f
        secondCount    = 0
        tenMinWh       = 0f
        tenMinDistKm   = 0f
        tenMinSpeedSum = 0f
        tenMinSamples  = 0
        tenMinStartMs  = 0L
        hourlyWh       = 0f
        hourlyDistKm   = 0f
        hourlySpeedSum = 0f
        hourlyBlocks   = 0
        hourlyStartMs  = 0L
        sessionWh     = 0f
        sessionDistKm = 0f
    }
}
