package com.mechrobotix.mechbot

import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class FrameSender(private val onStatus: (String) -> Unit) {
    companion object {
        const val VIDEO_PORT = 8989
    }

    private val main = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    @Volatile private var running = false

    fun start(info: WifiP2pInfo) {
        stop()
        running = true
        thread(name = "mechbot-video-sender") {
            try {
                val activeSocket = if (info.isGroupOwner) {
                    postStatus("Video: esperando receptor en puerto $VIDEO_PORT...")
                    serverSocket = ServerSocket(VIDEO_PORT)
                    serverSocket!!.accept()
                } else {
                    val host = info.groupOwnerAddress.hostAddress ?: return@thread
                    postStatus("Video: conectando a $host:$VIDEO_PORT...")
                    connectWithRetry(host, VIDEO_PORT)
                }
                socket = activeSocket
                output = DataOutputStream(BufferedOutputStream(activeSocket.getOutputStream()))
                postStatus("Video: enlace listo.")
            } catch (e: Exception) {
                if (running) postStatus("Video: error sender ${e.message}")
            }
        }
    }

    fun sendFrame(jpeg: ByteArray) {
        val out = output ?: return
        try {
            synchronized(this) {
                out.writeInt(jpeg.size)
                out.write(jpeg)
                out.flush()
            }
        } catch (e: Exception) {
            postStatus("Video: error enviando frame ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        output = null
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
