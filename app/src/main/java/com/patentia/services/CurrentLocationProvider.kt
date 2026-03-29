package com.patentia.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

class CurrentLocationProvider(
    context: Context,
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun isLocationEnabled(): Boolean {
        val manager = locationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): GeoPoint? {
        val location = runCatching {
            fusedLocationClient
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .await()
        }.getOrNull() ?: runCatching {
            fusedLocationClient.lastLocation.await()
        }.getOrNull()

        return location?.let {
            GeoPoint(latitude = it.latitude, longitude = it.longitude)
        }
    }
}