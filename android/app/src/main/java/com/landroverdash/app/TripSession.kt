package com.landroverdash.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trip_sessions")
data class TripSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime:          Long,    // epoch ms
    val endTime:            Long,
    val distanceKm:         Float,
    val durationMin:        Int,
    val startSOC:           Int,
    val endSOC:             Int,
    val energyKwh:          Float,
    val avgEfficiencyWhPerKm: Float,
    val avgSpeedKmh:        Float,
    val maxSpeedKmh:        Float
)
