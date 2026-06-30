package com.mechrobotix.mechbot

import android.graphics.BitmapFactory
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RemoteFrameReceiver(
    private val onStatus: (String) -> Unit,
    private val onFrame: (android.graphics.Bitmap) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    @Volatile private var running = false

    fun start(info: WifiP2pInfo) {
        stop()
        running = true
        thread(name = "mechbot-video-receiver") {
            try {
                val activeSocket = if (info.isGroupOwner) {
                    postStatus("Video: esperando al robot en puerto ${FrameSender.VIDEO_PORT}...")
                    serverSocket = ServerSocket(FrameSender.VIDEO_PORT)
                    serverSocket!!.accept()
                } else {
                    val host = info.groupOwnerAddress.hostAddress ?: return@thread
                    postStatus("Video: conectando al robot $host:${FrameSender.VIDEO_PORT}...")
                    connectWithRetry(host, FrameSender.VIDEO_PORT)
                }
                socket = activeSocket
                postStatus("Video: recibiendo frames.")
                val input = DataInputStream(BufferedInputStream(activeSocket.getInputStream()))

                while (running) {
                    val size = input.readInt()
                    if (size <= 0 || size > 2_000_000) continue
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) main.post { onFrame(bitmap) }
                }
            } catch (e: Exception) {
                if (running) postStatus("Video: error receiver ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        socket = null
        serverSocket = null
    }

    private fun connectWithRetry(host: String, port: Int): Socket {
        var lastError: Exception? = null
        repeat(30) {
            try {
                return Socket().apply { connect(InetSocketAddress(host, port), 1500) }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500)
            }
        }
        throw lastError ?: IllegalStateException("No se pudo conectar video")
    }

    private fun postStatus(message: String) = main.post { onStatus(message) }
}
