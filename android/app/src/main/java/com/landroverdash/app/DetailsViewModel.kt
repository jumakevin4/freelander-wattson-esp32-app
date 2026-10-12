package com.landroverdash.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class DetailsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).tripDao()

    val totalTrips        = dao.tripCount()
    val totalDistanceKm   = dao.totalDistanceKm()
    val totalDurationMin  = dao.totalDurationMin()
    val totalEnergyKwh    = dao.totalEnergyKwh()
    val avgEfficiency     = dao.avgEfficiencyWhPerKm()
    val avgSpeed          = dao.avgSpeedKmh()

    fun deleteAll() = viewModelScope.launch { dao.deleteAll() }
}
