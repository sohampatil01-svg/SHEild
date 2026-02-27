package com.example.fakecalldistress.logic

import android.location.Location
import android.util.Log
import com.example.fakecalldistress.data.SafetySettingsRepository
import kotlin.math.abs

class AnomalyDetector(private val repository: SafetySettingsRepository) {

    private val MAX_STATIONARY_TIME = 10 * 60 * 1000 // 10 minutes
    private val VEHICLE_SPEED_THRESHOLD = 10.0 // approx 36 km/h
    private val ROUTE_DEVIATION_THRESHOLD = 300 // 300 meters

    fun checkForAnomalies(currentLocation: Location, journeyHistory: List<Location>): AnomalyType {
        // 1. Vehicle Speed Detection
        if (currentLocation.hasSpeed() && currentLocation.speed > VEHICLE_SPEED_THRESHOLD) {
            return AnomalyType.SPEED_ANOMALY
        }

        // 2. Prolonged Inactivity
        if (journeyHistory.size > 5) {
            val lastPoints = journeyHistory.takeLast(5)
            val totalDistance = lastPoints.first().distanceTo(lastPoints.last())
            if (totalDistance < 10) { // Hasn't moved 10m in the last 5 logs
                return AnomalyType.STATIONARY_ANOMALY
            }
        }

        // 3. Route Deviation (requires at least one safe route)
        val safeRoutes = repository.getSafeRoutes()
        if (safeRoutes.isNotEmpty()) {
            // Simple logic: Is current point more than 300m away from ANY point in ANY safe route?
            // This is a lightweight on-device check.
            // (Implementation would parse JSON and compare distances)
        }

        return AnomalyType.NONE
    }

    enum class AnomalyType {
        NONE,
        SPEED_ANOMALY,
        STATIONARY_ANOMALY,
        ROUTE_DEVIATION
    }
}
