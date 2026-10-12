package com.landroverdash.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EnergyDao {

    // ── 10-minute blocks ──────────────────────────────────────────────────────
    @Insert
    suspend fun insertTenMin(block: EnergyTenMin)

    @Query("SELECT * FROM energy_ten_min ORDER BY timestamp DESC")
    fun getAllTenMin(): Flow<List<EnergyTenMin>>

    @Query("SELECT * FROM energy_ten_min WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getTenMinSince(since: Long): List<EnergyTenMin>

    @Query("DELETE FROM energy_ten_min WHERE timestamp < :before")
    suspend fun deleteTenMinBefore(before: Long)

    /**
     * Delete only the ten-minute blocks belonging to one aggregated hour.
     * Half-open so the block that starts exactly at [until] — the first of the
     * NEXT hour — is kept.
     */
    @Query("DELETE FROM energy_ten_min WHERE timestamp >= :from AND timestamp < :until")
    suspend fun deleteTenMinBetween(from: Long, until: Long)

    // ── Hourly records ────────────────────────────────────────────────────────
    @Insert
    suspend fun insertHourly(record: EnergyHourly)

    @Query("SELECT * FROM energy_hourly ORDER BY timestamp DESC")
    fun getAllHourly(): Flow<List<EnergyHourly>>

    @Query("SELECT COALESCE(SUM(totalWh), 0) FROM energy_hourly")
    fun lifetimeTotalWh(): Flow<Float>

    @Query("SELECT COALESCE(AVG(efficiencyWhPerKm), 0) FROM energy_hourly WHERE efficiencyWhPerKm > 0")
    fun lifetimeAvgEfficiency(): Flow<Float>

    @Query("DELETE FROM energy_ten_min")
    suspend fun deleteAllTenMin()

    @Query("DELETE FROM energy_hourly")
    suspend fun deleteAllHourly()
}
