package com.mechrobotix.mechbot

interface MotorController {
    fun prepare()
    fun isReady(): Boolean
    fun send(command: MovementCommand): Boolean
    fun close()
}
