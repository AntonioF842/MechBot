package com.mechrobotix.mechbot

enum class MechbotCommand(val wire: String) {
    FORWARD("F"),
    BACKWARD("B"),
    LEFT("L"),
    RIGHT("R"),
    STOP("S"),
    CAMERA_ON("CAMERA_ON"),
    CAMERA_OFF("CAMERA_OFF");

    companion object {
        fun fromWire(value: String): MechbotCommand? = values().firstOrNull { it.wire == value.trim() }
    }
}
