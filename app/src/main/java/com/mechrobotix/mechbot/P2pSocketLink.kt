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
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class P2pSocketLink(
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val authToken: String = DEFAULT_AUTH_TOKEN
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
                val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream()))

                // --- FASE DE AUTENTICACIÓN (HANDSHAKE) ---
                postStatus("Autenticando enlace...")
                activeSocket.soTimeout = AUTH_TIMEOUT_MS

                // 1. Enviar nuestro token de autenticación inmediatamente
                localWriter.write("$AUTH_PREFIX$authToken")
                localWriter.newLine()
                localWriter.flush()

                // 2. Leer y verificar el token del otro extremo
                val authLine = reader.readLine()?.trim() ?: ""
                if (!isValidAuthLine(authLine)) {
                    postStatus("Conexión rechazada: token de autenticación inválido.")
                    return@thread // Salta al bloque finally y cierra el socket
                }

                // 3. Autenticación exitosa: quitar timeout de lectura para operación normal
                activeSocket.soTimeout = 0

                synchronized(lock) {
                    if (session == sessionId) {
                        socket = activeSocket
                        writer = localWriter
                        connected = true
                    }
                }
                postStatus("Socket de comandos autenticado y conectado.")
                postConnection(true)

                while (running && session == sessionId) {
                    val line = reader.readLine() ?: break
                    // Ignorar tramas AUTH tardías si llegaran a ocurrir
                    if (line.startsWith(AUTH_PREFIX)) continue
                    main.post {
                        if (running && session == sessionId) onMessage(line)
                    }
                }
            } catch (error: Exception) {
                if (running && session == sessionId) {
                    postStatus("Error en socket de comandos: ${error.message}")
                }
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

    private fun isValidAuthLine(line: String): Boolean {
        if (!line.startsWith(AUTH_PREFIX)) return false
        val receivedToken = line.removePrefix(AUTH_PREFIX)
        // Comparación en tiempo constante para evitar timing attacks
        return MessageDigest.isEqual(
            receivedToken.toByteArray(Charsets.UTF_8),
            authToken.toByteArray(Charsets.UTF_8)
        )
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
        private const val AUTH_PREFIX = "AUTH:"
        private const val AUTH_TIMEOUT_MS = 3000
        // Cámbialo por tu clave secreta compartida (o pásalo desde BuildConfig / UI)
        const val DEFAULT_AUTH_TOKEN = "MECHBOT_SEC_2026_#99A1"
    }
}