package com.landroverdash.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripSession): Long

    @Query("SELECT * FROM trip_sessions ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripSession>>

    // Long-term aggregates
    @Query("SELECT COUNT(*) FROM trip_sessions")
    fun tripCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(distanceKm), 0) FROM trip_sessions")
    fun totalDistanceKm(): Flow<Float>

    @Query("SELECT COALESCE(SUM(energyKwh), 0) FROM trip_sessions")
    fun totalEnergyKwh(): Flow<Float>

    @Query("SELECT COALESCE(SUM(durationMin), 0) FROM trip_sessions")
    fun totalDurationMin(): Flow<Int>

    @Query("SELECT COALESCE(AVG(avgEfficiencyWhPerKm), 0) FROM trip_sessions")
    fun avgEfficiencyWhPerKm(): Flow<Float>

    @Query("SELECT COALESCE(AVG(avgSpeedKmh), 0) FROM trip_sessions")
    fun avgSpeedKmh(): Flow<Float>

    @Query("SELECT * FROM trip_sessions WHERE startTime >= :since ORDER BY startTime ASC")
    suspend fun getTripsLast14Days(since: Long): List<TripSession>

    // Delete all history
    @Query("DELETE FROM trip_sessions")
    suspend fun deleteAll()
}
