package com.mechrobotix.mechbot

import android.util.Log

interface MotorController {
    fun send(command: MechbotCommand)
    fun close()
}

class MockMotorController : MotorController {
    override fun send(command: MechbotCommand) {
        if (command in listOf(
                MechbotCommand.FORWARD,
                MechbotCommand.BACKWARD,
                MechbotCommand.LEFT,
                MechbotCommand.RIGHT,
                MechbotCommand.STOP
            )
        ) {
            Log.d("MechbotMotor", "Comando para motor: ${command.wire}")
        }
    }

    override fun close() = Unit
}
