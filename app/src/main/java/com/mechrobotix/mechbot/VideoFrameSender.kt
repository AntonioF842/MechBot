package com.mechrobotix.mechbot

import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VideoFrameSender(private val onStatus: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val frames = LinkedBlockingQueue<VideoFramePacket>(1)
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    @Volatile private var running = false
    @Volatile private var sessionId = 0L

    fun start(info: WifiP2pInfo) {
        stop()
        running = true
        val session = ++sessionId
        thread(name = "mechbot-video-sender") {
            var localServer: ServerSocket? = null
            var localSocket: Socket? = null
            var localOutput: DataOutputStream? = null
            try {
                val activeSocket = if (info.isGroupOwner) {
                    postStatus("Video: esperando receptor en puerto $VIDEO_PORT...")
                    ServerSocket(VIDEO_PORT).also {
                        it.reuseAddress = true
                        localServer = it
                        synchronized(lock) {
                            if (session == sessionId) serverSocket = it
                        }
                    }.accept()
                } else {
                    val host = info.groupOwnerAddress.hostAddress ?: return@thread
                    postStatus("Video: conectando a $host:$VIDEO_PORT...")
                    connectWithRetry(host, VIDEO_PORT, session)
                }
                localSocket = activeSocket
                if (!running || session != sessionId) return@thread
                activeSocket.tcpNoDelay = true
                activeSocket.keepAlive = true
                activeSocket.sendBufferSize = 256 * 1024
                val stream = DataOutputStream(BufferedOutputStream(activeSocket.getOutputStream(), 128 * 1024))
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
                    VideoProtocol.write(stream, frame)
                    stream.flush()
                }
            } catch (error: Exception) {
                if (running && session == sessionId) postStatus("Video: error sender ${error.message}")
            } finally {
                runCatching { localOutput?.close() }
                runCatching { localSocket?.close() }
                runCatching { localServer?.close() }
                synchronized(lock) {
                    if (session == sessionId) {
                        output = null
                        socket = null
                        serverSocket = null
                    }
                }
            }
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
            runCatching { serverSocket?.close() }
            output = null
            socket = null
            serverSocket = null
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
        throw lastError ?: IllegalStateException("No se pudo conectar video")
    }

    private fun postStatus(message: String) = main.post { onStatus(message) }

    companion object {
        const val VIDEO_PORT = 8989
    }
}
