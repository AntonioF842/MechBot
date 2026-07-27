package com.mechrobotix.mechbot

enum class ObjectProximity(val text: String) {
    VERY_CLOSE("Muy cerca"),
    CLOSE("Cerca"),
    MEDIUM("Distancia media"),
    FAR("Lejos"),
    VERY_FAR("Muy lejos");

    companion object {
        fun fromWire(value: String): ObjectProximity = entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

data class DetectionResult(
    val trackingId: Int,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val proximity: ObjectProximity
)
