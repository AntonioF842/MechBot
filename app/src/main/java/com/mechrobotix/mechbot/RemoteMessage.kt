package com.mechrobotix.mechbot

sealed interface RemoteMessage {
    data class Movement(val command: MovementCommand) : RemoteMessage
    data object VideoOn : RemoteMessage
    data object VideoOff : RemoteMessage
    data object DetectionOn : RemoteMessage
    data object DetectionOff : RemoteMessage
    data class DetectionState(val enabled: Boolean) : RemoteMessage
    data object Ping : RemoteMessage

    fun toWire(): String = when (this) {
        is Movement -> "MOVE:${command.wire}"
        VideoOn -> "VIDEO:ON"
        VideoOff -> "VIDEO:OFF"
        DetectionOn -> "DETECTION:ON"
        DetectionOff -> "DETECTION:OFF"
        is DetectionState -> if (enabled) "STATE:DETECTION:ON" else "STATE:DETECTION:OFF"
        Ping -> "PING"
    }

    companion object {
        fun fromWire(value: String): RemoteMessage? {
            val text = value.trim()
            return when {
                text.startsWith("MOVE:") -> MovementCommand.fromWire(text.substringAfter("MOVE:"))?.let(::Movement)
                text == "VIDEO:ON" -> VideoOn
                text == "VIDEO:OFF" -> VideoOff
                text == "DETECTION:ON" -> DetectionOn
                text == "DETECTION:OFF" -> DetectionOff
                text == "STATE:DETECTION:ON" -> DetectionState(true)
                text == "STATE:DETECTION:OFF" -> DetectionState(false)
                text == "PING" -> Ping
                else -> MovementCommand.fromWire(text)?.let(::Movement)
            }
        }
    }
}
