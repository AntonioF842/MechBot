package com.mechrobotix.mechbot

enum class MovementCommand(val wire: String, val displayName: String) {
    FORWARD("F", "Adelante"),
    BACKWARD("B", "Atrás"),
    LEFT("L", "Izquierda"),
    RIGHT("R", "Derecha"),
    STOP("S", "Detenido");

    companion object {
        fun fromWire(value: String): MovementCommand? = entries.firstOrNull { it.wire == value.trim() }
    }
}
