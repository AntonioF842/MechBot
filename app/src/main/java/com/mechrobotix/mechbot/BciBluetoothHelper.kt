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

    // --- VARIABLES PARA FILTRO ANTI-RUIDO (DEBOUNCE / ESTABILIZACIÓN) ---
    private var lastCandidateCommand: MovementCommand? = null
    private var candidateHitCount = 0
    private var activeConfirmedCommand: MovementCommand = MovementCommand.STOP

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
                resetDebounceFilter()
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
            val parsedCommand = when (rawCommand) {
                "1" -> MovementCommand.FORWARD
                "2" -> MovementCommand.LEFT
                "3" -> MovementCommand.RIGHT
                "0", "P", "?" -> MovementCommand.STOP
                else -> MovementCommand.STOP
            }

            // Extracción de porcentaje de confianza (columnas 22-25, índices 21-24)
            val confidenceStr = if (tokens.size >= 25) {
                val idx = when (rawCommand) {
                    "1" -> 22 // prob1
                    "2" -> 23 // prob2
                    "3" -> 24 // prob3
                    else -> 21 // prob0
                }
                tokens.getOrNull(idx)?.trim()?.let { " (${it}% conf)" } ?: ""
            } else ""

            // --- APLICACIÓN DEL FILTRO ANTI-RUIDO ---
            val filterCommand = filterIncomingCommand(parsedCommand) ?: return

            mainHandler.post {
                onBrainCommandDetected(filterCommand, confidenceStr)
            }
        }
    }

    /**
     * Evalúa si un comando recibido debe emitirse o ignorarse por fluctuación de señal.
     * Los comandos STOP se emiten inmediatamente sin retardo por seguridad.
     */
    private fun filterIncomingCommand(newCommand: MovementCommand): MovementCommand? {
        // 1. Prioridad de Seguridad: STOP inmediato sin retardo
        if (newCommand == MovementCommand.STOP) {
            resetDebounceFilter()
            if (activeConfirmedCommand != MovementCommand.STOP) {
                activeConfirmedCommand = MovementCommand.STOP
                return MovementCommand.STOP
            }
            return null
        }

        // 2. Si el comando es idéntico al ya confirmado, no se reemite redundantemente
        if (newCommand == activeConfirmedCommand) {
            lastCandidateCommand = newCommand
            candidateHitCount = REQUIRED_STABLE_PACKETS
            return null
        }

        // 3. Conteo de confirmaciones consecutivas para cambios de estado (Giro / Avance)
        if (newCommand == lastCandidateCommand) {
            candidateHitCount++
        } else {
            lastCandidateCommand = newCommand
            candidateHitCount = 1
        }

        // 4. Se confirma el nuevo comando únicamente tras alcanzar las lecturas consecutivas requeridas
        if (candidateHitCount >= REQUIRED_STABLE_PACKETS) {
            activeConfirmedCommand = newCommand
            return newCommand
        }

        return null
    }

    private fun resetDebounceFilter() {
        lastCandidateCommand = null
        candidateHitCount = 0
        activeConfirmedCommand = MovementCommand.STOP
    }

    fun disconnect() {
        if (!isConnected && socket == null) return
        isConnected = false
        resetDebounceFilter()
        postStatus("Casco BCI desconectado.")
        runCatching {
            socket?.inputStream?.close()
            socket?.outputStream?.close()
            socket?.close()
        }
        socket = null
    }

    private fun postStatus(message: String) {
        mainHandler.post { onStatusMessage(message) }
    }

    private companion object {
        // Número de paquetes CSV idénticos y consecutivos requeridos para validar un cambio de comando mental
        private const val REQUIRED_STABLE_PACKETS = 2
    }
}