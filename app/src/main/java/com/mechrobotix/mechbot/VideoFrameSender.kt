package com.mechrobotix.mechbot

import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VideoFrameSender(private val onStatus: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val frames = LinkedBlockingQueue<VideoFramePacket>(1)
    private val lock = Any()
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    @Volatile private var running = false
    @Volatile private var sessionId = 0L

    // Calidad JPEG dinámica ajustada según la velocidad de envío en la red (25..75)
    @Volatile var recommendedQuality: Int = 65
        private set

    fun start(info: WifiP2pInfo, socketLink: P2pSocketLink? = null) {
        stop()
        running = true
        recommendedQuality = 65
        val session = ++sessionId
        thread(name = "mechbot-video-sender") {
            var localSocket: Socket? = null
            var localOutput: DataOutputStream? = null
            try {
                // Resolver la IP destino correcta dinámicamente
                val targetHost = if (info.isGroupOwner) {
                    // Si este teléfono (Robot) es GO, enviar a la IP del cliente remoto conectado
                    socketLink?.remoteClientIp ?: "192.168.49.2"
                } else {
                    // Si es cliente, enviar a la IP del GO
                    info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                }

                postStatus("Video: conectando al controlador en $targetHost:$VIDEO_PORT...")
                val activeSocket = connectWithRetry(targetHost, VIDEO_PORT, session)
                localSocket = activeSocket

                if (!running || session != sessionId) return@thread
                activeSocket.tcpNoDelay = true
                activeSocket.keepAlive = true
                // Búfer reducido (32 KB) para evitar que TCP encole fotogramas atrasados
                activeSocket.sendBufferSize = 32 * 1024
                val stream = DataOutputStream(BufferedOutputStream(activeSocket.getOutputStream(), 16 * 1024))
                localOutput = stream
                synchronized(lock) {
                    if (session == sessionId) {
                        socket = activeSocket
                        output = stream
                    }
                }
                postStatus("Video: enlace listo.")
                while (running && session == sessionId) {
                    val frame = frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val startNs = System.nanoTime()
                    VideoProtocol.write(stream, frame)
                    stream.flush()
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    adjustQuality(elapsedMs)
                }
            } catch (error: Exception) {
                if (running && session == sessionId) postStatus("Video: error sender ${error.message}")
            } finally {
                runCatching { localOutput?.close() }
                runCatching { localSocket?.close() }
                synchronized(lock) {
                    if (session == sessionId) {
                        output = null
                        socket = null
                    }
                }
            }
        }
    }

    private fun adjustQuality(sendTimeMs: Long) {
        recommendedQuality = when {
            sendTimeMs > 80L -> 25  // Red muy congestionada: máxima compresión
            sendTimeMs > 45L -> 40  // Red moderada
            sendTimeMs < 20L -> (recommendedQuality + 5).coerceAtMost(75) // Recuperar calidad gradualmente
            else -> recommendedQuality
        }
    }

    fun sendFrame(frame: VideoFramePacket) {
        if (!running) return
        frames.clear()
        frames.offer(frame)
    }

    fun stop() {
        running = false
        sessionId++
        frames.clear()
        synchronized(lock) {
            runCatching { output?.close() }
            runCatching { socket?.close() }
            output = null
            socket = null
        }
    }

    private fun connectWithRetry(host: String, port: Int, session: Long): Socket {
        var lastError: Exception? = null
        repeat(30) {
            if (!running || session != sessionId) throw IllegalStateException("Conexión cancelada")
            try {
                return Socket().apply { connect(InetSocketAddress(host, port), 1500) }
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("No se pudo conectar transmisión de video")
    }

    private fun postStatus(message: String) = main.post { onStatus(message) }

    companion object {
        const val VIDEO_PORT = 8989
    }
}