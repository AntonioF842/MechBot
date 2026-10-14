package com.mechrobotix.mechbot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread

class BciBluetoothManager(
    private val context: Context,
    private val onBrainCommandDetected: (MovementCommand, String) -> Unit,
    private val onStatusMessage: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private var socket: BluetoothSocket? = null
    @Volatile private var isConnected = false
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        disconnect()
        thread(name = "mechbot-bci-bt") {
            try {
                postStatus("Conectando a casco BCI (${device.name ?: device.address})...")
                bluetoothAdapter?.cancelDiscovery()

                val btSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                btSocket.connect()
                socket = btSocket
                isConnected = true
                postStatus("Casco BCI EEG conectado (${device.name ?: device.address}).")

                val reader = BufferedReader(InputStreamReader(btSocket.inputStream))
                while (isConnected) {
                    val csvLine = reader.readLine() ?: break
                    processCsvPacket(csvLine)
                }
            } catch (e: Exception) {
                if (isConnected) {
                    postStatus("Error en casco BCI: ${e.message}")
                }
            } finally {
                disconnect()
            }
        }
    }

    private fun processCsvPacket(csv: String) {
        val tokens = csv.split(",")
        // Columna 12 (índice 11) corresponde a 'lastCommand' (0..3, P)
        if (tokens.size >= 12) {
            val rawCommand = tokens[11].trim()
            val movementCommand = when (rawCommand) {
                "1" -> MovementCommand.FORWARD
                "2" -> MovementCommand.LEFT
                "3" -> MovementCommand.RIGHT
                "0", "P", "?" -> MovementCommand.STOP
                else -> MovementCommand.STOP
            }

            // Opcional: Extraer porcentaje de confianza si está presente
            val confidenceStr = if (tokens.size >= 25) {
                val idx = when (rawCommand) {
                    "1" -> 22 // prob1
                    "2" -> 23 // prob2
                    "3" -> 24 // prob3
                    else -> 21 // prob0
                }
                tokens.getOrNull(idx)?.trim()?.let { " (${it}% conf)" } ?: ""
            } else ""

            mainHandler.post {
                onBrainCommandDetected(movementCommand, confidenceStr)
            }
        }
    }

    fun disconnect() {
        if (!isConnected && socket == null) return
        isConnected = false
        postStatus("Casco BCI desconectado.")
        runCatching { socket?.close() }
        socket = null
    }

    private fun postStatus(message: String) {
        mainHandler.post { onStatusMessage(message) }
    }
}