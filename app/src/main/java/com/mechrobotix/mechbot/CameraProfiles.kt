package com.mechrobotix.mechbot

object CameraProfiles {
    val FRONT_CAMERA = CameraGeometry(
        heightMeters = 0.60f,
        pitchDownDegrees = 20f,
        verticalFovDegrees = 50f,
        maxReliableMeters = 5f
    )

    val REAR_CAMERA = CameraGeometry(
        heightMeters = 0.60f,
        pitchDownDegrees = 20f,
        verticalFovDegrees = 50f,
        maxReliableMeters = 5f
    )
}