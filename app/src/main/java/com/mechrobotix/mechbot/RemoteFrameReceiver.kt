package com.mechrobotix.mechbot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RemoteFrameReceiver(
    private val onStatus: (String) -> Unit,
    private val onFrame: (RemoteVideoFrame) -> Unit
) {
    private data class PendingFrame(val session: Long, val frame: RemoteVideoFrame)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pendingFrame = AtomicReference<PendingFrame?>()
    private val renderScheduled = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    @Volatile private var running = false
    @Volatile private var sessionId = 0L

    private val renderRunnable = object : Runnable {
        override fun run() {
            val pending = pendingFrame.getAndSet(null)
            if (pending != null) {
                if (running && pending.session == sessionId) {
                    onFrame(pending.frame)
                } else {
                    recycle(pending.frame.bitmap)
                }
            }
            renderScheduled.set(false)
            if (pendingFrame.get() != null && renderScheduled.compareAndSet(false, true)) {
                main.post(this)
            }
        }
    }

    fun start(info: WifiP2pInfo) {
        stop()
        running = true
        val session = ++sessionId
        thread(name = "mechbot-video-receiver") {
            var localServer: ServerSocket? = null
            var localSocket: Socket? = null
            try {
                val activeSocket = if (info.isGroupOwner) {
                    postStatus("Video: esperando al robot en puerto ${VideoFrameSender.VIDEO_PORT}...")
                    ServerSocket(VideoFrameSender.VIDEO_PORT).also {
                        it.reuseAddress = true
                        localServer = it
                        synchronized(lock) {
                            if (session == sessionId) serverSocket = it
                        }
                    }.accept()
                } else {
                    val host = info.groupOwnerAddress.hostAddress ?: return@thread
                    postStatus("Video: conectando al robot $host:${VideoFrameSender.VIDEO_PORT}...")
                    connectWithRetry(host, VideoFrameSender.VIDEO_PORT, session)
                }
                localSocket = activeSocket
                if (!running || session != sessionId) return@thread
                activeSocket.tcpNoDelay = true
                activeSocket.keepAlive = true
                activeSocket.receiveBufferSize = 256 * 1024
                synchronized(lock) {
                    if (session == sessionId) socket = activeSocket
                }
                postStatus("Video: recibiendo frames.")
                val input = DataInputStream(BufferedInputStream(activeSocket.getInputStream(), 128 * 1024))
                while (running && session == sessionId) {
                    val packet = VideoProtocol.read(input)
                    val decoded = BitmapFactory.decodeByteArray(
                        packet.jpeg,
                        0,
                        packet.jpeg.size,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    ) ?: continue
                    val bitmap = transform(decoded, packet.rotationDegrees, packet.mirrored)
                    offerFrame(
                        session,
                        RemoteVideoFrame(
                            bitmap = bitmap,
                            frameId = packet.frameId,
                            timestampMs = packet.timestampMs,
                            detectionEnabled = packet.detectionEnabled,
                            detections = packet.detections
                        )
                    )
                }
            } catch (error: Exception) {
                if (running && session == sessionId) postStatus("Video: error receiver ${error.message}")
            } finally {
                runCatching { localSocket?.close() }
                runCatching { localServer?.close() }
                synchronized(lock) {
                    if (session == sessionId) {
                        socket = null
                        serverSocket = null
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        sessionId++
        synchronized(lock) {
            runCatching { socket?.close() }
            runCatching { serverSocket?.close() }
            socket = null
            serverSocket = null
        }
        pendingFrame.getAndSet(null)?.let { recycle(it.frame.bitmap) }
    }

    private fun offerFrame(session: Long, frame: RemoteVideoFrame) {
        if (!running || session != sessionId) {
            recycle(frame.bitmap)
            return
        }
        pendingFrame.getAndSet(PendingFrame(session, frame))?.let { recycle(it.frame.bitmap) }
        if (renderScheduled.compareAndSet(false, true)) main.post(renderRunnable)
    }

    private fun transform(bitmap: Bitmap, rotationDegrees: Int, mirrored: Boolean): Bitmap {
        if (rotationDegrees == 0 && !mirrored) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) recycle(bitmap)
        return transformed
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

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun postStatus(message: String) = main.post { onStatus(message) }
}
