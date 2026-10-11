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
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class P2pSocketLink(
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var sessionId = 0L

    // Guarda la IP remota detectada del cliente o del host conectado
    @Volatile var remoteClientIp: String? = null
        private set

    fun start(info: WifiP2pInfo) {
        stop()
        running = true
        remoteClientIp = null
        val session = ++sessionId
        thread(name = "mechbot-command-socket") {
            var localServer: ServerSocket? = null
            var localSocket: Socket? = null
            var localWriter: BufferedWriter? = null
            try {
                val activeSocket = if (info.isGroupOwner) {
                    postStatus("Esperando socket de comandos en puerto $COMMAND_PORT...")
                    val server = ServerSocket(COMMAND_PORT).also {
                        it.reuseAddress = true
                        localServer = it
                        synchronized(lock) {
                            if (session == sessionId) serverSocket = it
                        }
                    }
                    val accepted = server.accept()
                    // Capturar la IP del cliente remoto que se conectó a este Group Owner
                    remoteClientIp = accepted.inetAddress?.hostAddress
                    accepted
                } else {
                    val host = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    remoteClientIp = host
                    postStatus("Conectando socket de comandos a $host:$COMMAND_PORT...")
                    connectWithRetry(host, COMMAND_PORT, session)
                }
                localSocket = activeSocket
                if (!running || session != sessionId) return@thread
                activeSocket.tcpNoDelay = true
                activeSocket.keepAlive = true
                localWriter = BufferedWriter(OutputStreamWriter(activeSocket.getOutputStream()))
                synchronized(lock) {
                    if (session == sessionId) {
                        socket = activeSocket
                        writer = localWriter
                        connected = true
                    }
                }
                postStatus("Socket de comandos conectado.")
                postConnection(true)
                val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream()))
                while (running && session == sessionId) {
                    val line = reader.readLine() ?: break
                    main.post {
                        if (running && session == sessionId) onMessage(line)
                    }
                }
            } catch (error: Exception) {
                if (running && session == sessionId) postStatus("Error en socket de comandos: ${error.message}")
            } finally {
                runCatching { localWriter?.close() }
                runCatching { localSocket?.close() }
                runCatching { localServer?.close() }
                var notify = false
                synchronized(lock) {
                    if (session == sessionId) {
                        notify = connected
                        connected = false
                        writer = null
                        socket = null
                        serverSocket = null
                    }
                }
                if (notify) postConnection(false)
            }
        }
    }

    fun sendLine(line: String): Boolean {
        if (!connected) return false
        sendExecutor.execute {
            try {
                synchronized(lock) {
                    writer?.apply {
                        write(line)
                        newLine()
                        flush()
                    }
                }
            } catch (error: Exception) {
                postStatus("No se pudo enviar comando: ${error.message}")
            }
        }
        return true
    }

    fun isConnected(): Boolean = connected

    fun stop() {
        running = false
        sessionId++
        remoteClientIp = null
        var notify = false
        synchronized(lock) {
            notify = connected
            connected = false
            runCatching { writer?.close() }
            runCatching { socket?.close() }
            runCatching { serverSocket?.close() }
            writer = null
            socket = null
            serverSocket = null
        }
        if (notify) postConnection(false)
    }

    fun close() {
        stop()
        sendExecutor.shutdownNow()
    }

    private fun connectWithRetry(host: String, port: Int, session: Long): Socket {
        var lastError: Exception? = null
        repeat(20) {
            if (!running || session != sessionId) throw IllegalStateException("Conexión cancelada")
            try {
                return Socket().apply { connect(InetSocketAddress(host, port), 1500) }
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("No se pudo conectar")
    }

    private fun postStatus(message: String) = main.post { onStatus(message) }

    private fun postConnection(value: Boolean) = main.post { onConnectionChanged(value) }

    companion object {
        const val COMMAND_PORT = 8988
    }
}