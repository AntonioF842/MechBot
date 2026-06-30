package com.mechrobotix.mechbot

import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class P2pSocketLink(
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    companion object {
        const val COMMAND_PORT = 8988
    }

    private val main = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    @Volatile private var running = false

    fun start(info: WifiP2pInfo) {
        stop()
        running = true
        thread(name = "mechbot-command-socket") {
            try {
                val activeSocket = if (info.isGroupOwner) {
                    postStatus("Esperando socket de comandos en puerto $COMMAND_PORT...")
                    serverSocket = ServerSocket(COMMAND_PORT)
                    serverSocket!!.accept()
                } else {
                    val host = info.groupOwnerAddress.hostAddress ?: return@thread
                    postStatus("Conectando socket de comandos a $host:$COMMAND_PORT...")
                    connectWithRetry(host, COMMAND_PORT)
                }

                socket = activeSocket
                writer = BufferedWriter(OutputStreamWriter(activeSocket.getOutputStream()))
                postStatus("Socket de comandos conectado.")

                val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream()))
                while (running) {
                    val line = reader.readLine() ?: break
                    main.post { onMessage(line) }
                }
            } catch (e: Exception) {
                if (running) postStatus("Error en socket de comandos: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun sendLine(line: String) {
        thread(name = "mechbot-command-send") {
            try {
                synchronized(this) {
                    writer?.apply {
                        write(line)
                        newLine()
                        flush()
                    } ?: postStatus("Aún no hay socket de comandos.")
                }
            } catch (e: Exception) {
                postStatus("No se pudo enviar comando: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
        serverSocket = null
    }

    private fun connectWithRetry(host: String, port: Int): Socket {
        var lastError: Exception? = null
        repeat(20) {
            try {
                return Socket().apply { connect(InetSocketAddress(host, port), 1500) }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("No se pudo conectar")
    }

    private fun postStatus(message: String) = main.post { onStatus(message) }
}
