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

    /**
     * Wh/km over all saved trips: total energy ÷ total distance.
     *
     * This was AVG(avgEfficiencyWhPerKm), the plain mean of every trip's own
     * figure. A 1 km roll down the hill counted as much as a 50 km drive, and
     * a trip saved with no energy measured (0 Wh/km) pulled the mean down, so
     * "your average" read far better than the car really does: on the history
     * this replaced, 2,136 km on 371 kWh is 5.8 km/kWh, but it showed 7.0.
     * Trips with no energy recorded are left out of both totals.
     */
    @Query("""
        SELECT COALESCE(SUM(energyKwh) * 1000.0 / NULLIF(SUM(distanceKm), 0), 0)
        FROM trip_sessions WHERE energyKwh != 0 AND distanceKm > 0""")
    fun avgEfficiencyWhPerKm(): Flow<Float>

    /**
     * Average moving speed over all trips: total distance ÷ total moving time,
     * where each trip's moving time is its distance ÷ its own average speed.
     * Was AVG(avgSpeedKmh), which weighted a 2-minute hop like a 2-hour drive.
     */
    @Query("""
        SELECT COALESCE(SUM(distanceKm) / NULLIF(SUM(distanceKm / avgSpeedKmh), 0), 0)
        FROM trip_sessions WHERE avgSpeedKmh > 0 AND distanceKm > 0""")
    fun avgSpeedKmh(): Flow<Float>

    @Query("SELECT * FROM trip_sessions WHERE startTime >= :since ORDER BY startTime ASC")
    suspend fun getTripsLast14Days(since: Long): List<TripSession>

    // Delete all history
    @Query("DELETE FROM trip_sessions")
    suspend fun deleteAll()
}
