package com.mechrobotix.mechbot

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationHelper(
    private val context: Context,
    private val onLocationUpdated: (latitude: Double, longitude: Double, speedMps: Float) -> Unit
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var isUpdating = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location: Location = result.lastLocation ?: return
            onLocationUpdated(location.latitude, location.longitude, location.speed)
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (isUpdating) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L) // Cada 2 segundos
            .setMinUpdateIntervalMillis(1000L)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        isUpdating = true
    }

    fun stopLocationUpdates() {
        if (!isUpdating) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isUpdating = false
    }
}