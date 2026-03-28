package com.patentia.services

import android.annotation.SuppressLint
import android.content.Context
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

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): GeoPoint? {
        val location = fusedLocationClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .await()

        return location?.let {
            GeoPoint(latitude = it.latitude, longitude = it.longitude)
        }
    }
}