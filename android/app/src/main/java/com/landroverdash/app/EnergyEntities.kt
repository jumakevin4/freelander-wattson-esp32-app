package com.landroverdash.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-second V×I sample — kept in memory, never written to DB.
 * Used to build 10-minute blocks.
 */
data class EnergySecond(
    val timestamp: Long,
    val voltageV:  Float,
    val currentA:  Float,
    val whSample:  Float   // V × I / 3600
)

/**
 * 10-minute aggregate — persisted to DB.
 * Six of these roll up into one EnergyHourly record.
 */
@Entity(tableName = "energy_ten_min")
data class EnergyTenMin(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp:    Long,   // epoch ms of block start
    val totalWh:      Float,
    val distanceKm:   Float,
    val avgSpeedKmh:  Float
)

/**
 * Hourly aggregate — permanent long-term record.
 * Written once per hour from six EnergyTenMin blocks.
 */
@Entity(tableName = "energy_hourly")
data class EnergyHourly(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp:         Long,   // epoch ms of hour start
    val totalWh:           Float,
    val distanceKm:        Float,
    val efficiencyWhPerKm: Float,  // totalWh / distanceKm
    val avgSpeedKmh:       Float
)
