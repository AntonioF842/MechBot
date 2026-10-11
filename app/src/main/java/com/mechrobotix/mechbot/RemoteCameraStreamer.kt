package com.mechrobotix.mechbot

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RemoteCameraStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onStatus: (String) -> Unit
) {
    private val cameraExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("mechbot-remote-camera"))
    private var detectionExecutor: ExecutorService? = null
    private val encodingExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        namedThreadFactory("mechbot-remote-jpeg"),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private var detector: ObjectDetectionEngine? = null
    private val detectorLock = Any()
    private val smoother = DetectionSmoother()
    private val detectionBusy = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lastVideoFrameTime = 0L
    private var lastDetectionFrameTime = 0L
    private var lastDetectionErrorTime = 0L
    private var lastEncodingErrorTime = 0L
    private var frameId = 0L

    @Volatile private var latestDetections: List<DetectionResult> = emptyList()
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var detectionEnabled = false
    @Volatile private var detectionGeneration = 0L
    @Volatile private var sessionId = 0L

    // Control del selector de cámara (trasera por defecto para avanzar, frontal para reversa)
    @Volatile private var currentSender: VideoFrameSender? = null
    @Volatile private var useBackwardLens = false

    fun setDetectionEnabled(enabled: Boolean) {
        detectionEnabled = enabled
        detectionGeneration++
        smoother.reset()
        latestDetections = emptyList()
        lastDetectionFrameTime = 0L
        onStatus(if (enabled) "Detección remota habilitada." else "Detección remota deshabilitada.")
    }

    fun isDetectionEnabled(): Boolean = detectionEnabled

    fun setCameraLens(useBackwardLens: Boolean) {
        if (this.useBackwardLens == useBackwardLens) return
        this.useBackwardLens = useBackwardLens

        // Si la cámara está transmitiendo activamente, reconfigurar el lente
        val sender = currentSender
        if (running && sender != null) {
            start(sender)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(sender: VideoFrameSender) {
        if (closed) return
        currentSender = sender
        stop()
        running = true
        smoother.reset()
        latestDetections = emptyList()
        lastVideoFrameTime = 0L
        lastDetectionFrameTime = 0L
        val session = ++sessionId
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!isActive(session)) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // LÓGICA INVERTIDA:
                // Si useBackwardLens == true (Retroceder) -> Usar Cámara TRASERA
                // Si useBackwardLens == false (Avanzar/Girar) -> Usar Cámara FRONTAL
                val selector = if (useBackwardLens) {
                    if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                } else {
                    if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                }

                val isFrontCamera = selector == CameraSelector.DEFAULT_FRONT_CAMERA

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 360))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis = analysis
                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyze(image, isFrontCamera, sender, session)
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                onStatus(if (isFrontCamera) "Cámara frontal activa (Frente/Giro)." else "Cámara trasera activa (Reversa).")
            } catch (error: Exception) {
                if (session == sessionId) running = false
                onStatus("No se pudo iniciar cámara remota: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(
        image: ImageProxy,
        mirrored: Boolean,
        sender: VideoFrameSender,
        session: Long
    ) {
        if (!isActive(session)) {
            image.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoFrameTime < VIDEO_FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastVideoFrameTime = now
        val yuvFrame = try {
            YuvFrameEncoder.copyFrame(image, mirrored)
        } catch (error: Exception) {
            image.close()
            onStatus("Cámara: error copiando frame ${error.message}")
            return
        }
        val packetFrameId = ++frameId
        val packetTimestamp = System.currentTimeMillis()
        val packetDetectionEnabled = detectionEnabled
        val packetDetectionGeneration = detectionGeneration
        val packetDetections = if (packetDetectionEnabled) latestDetections else emptyList()
        try {
            encodingExecutor.execute {
                if (!isActive(session)) return@execute
                try {
                    val jpeg = YuvFrameEncoder.encodeJpeg(yuvFrame)
                    if (isActive(session)) {
                        val detectionStillValid = packetDetectionEnabled &&
                                detectionEnabled &&
                                packetDetectionGeneration == detectionGeneration
                        sender.sendFrame(
                            VideoFramePacket(
                                frameId = packetFrameId,
                                timestampMs = packetTimestamp,
                                width = yuvFrame.width,
                                height = yuvFrame.height,
                                rotationDegrees = yuvFrame.rotationDegrees,
                                mirrored = yuvFrame.mirrored,
                                detectionEnabled = detectionStillValid,
                                detections = if (detectionStillValid) packetDetections else emptyList(),
                                jpeg = jpeg
                            )
                        )
                    }
                } catch (error: Exception) {
                    reportEncodingError(error, now, session)
                }
            }
        } catch (error: RejectedExecutionException) {
            reportEncodingError(error, now, session)
        }
        val shouldDetect = detectionEnabled &&
                now - lastDetectionFrameTime >= DETECTION_FRAME_INTERVAL_MS &&
                detectionBusy.compareAndSet(false, true)
        if (!shouldDetect) {
            image.close()
            return
        }
        lastDetectionFrameTime = now
        val generation = detectionGeneration
        try {
            ensureDetector().process(
                imageProxy = image,
                mirror = mirrored,
                onResult = { rawDetections ->
                    if (isActive(session) && detectionEnabled && generation == detectionGeneration) {
                        latestDetections = smoother.update(rawDetections, SystemClock.elapsedRealtime())
                    }
                },
                onFailure = { error ->
                    if (isActive(session) && detectionEnabled && generation == detectionGeneration) {
                        latestDetections = smoother.update(emptyList(), SystemClock.elapsedRealtime())
                        reportDetectionError(error, SystemClock.elapsedRealtime(), session)
                    }
                },
                onComplete = {
                    image.close()
                    detectionBusy.set(false)
                    if (closed) closeDetectionResources()
                }
            )
        } catch (error: Exception) {
            image.close()
            detectionBusy.set(false)
            if (closed) closeDetectionResources()
            if (isActive(session) && detectionEnabled && generation == detectionGeneration) {
                latestDetections = smoother.update(emptyList(), SystemClock.elapsedRealtime())
                reportDetectionError(error, SystemClock.elapsedRealtime(), session)
            }
        }
    }

    fun stop() {
        running = false
        sessionId++
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        encodingExecutor.queue.clear()
        smoother.reset()
        latestDetections = emptyList()
        lastVideoFrameTime = 0L
        lastDetectionFrameTime = 0L
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        if (!detectionBusy.get()) closeDetectionResources()
        cameraExecutor.shutdown()
        encodingExecutor.shutdownNow()
    }

    private fun closeDetectionResources() = synchronized(detectorLock) {
        detector?.close()
        detector = null
        detectionExecutor?.shutdown()
        detectionExecutor = null
    }

    private fun ensureDetector(): ObjectDetectionEngine = synchronized(detectorLock) {
        check(!closed)
        detector ?: run {
            val executor = Executors.newSingleThreadExecutor(namedThreadFactory("mechbot-remote-detection"))
            detectionExecutor = executor
            ObjectDetectionEngine(executor).also { detector = it }
        }
    }

    private fun isActive(session: Long): Boolean = running && session == sessionId

    private fun reportDetectionError(error: Exception, timestampMs: Long, session: Long) {
        if (isActive(session) && timestampMs - lastDetectionErrorTime >= ERROR_INTERVAL_MS) {
            lastDetectionErrorTime = timestampMs
            onStatus("Detección remota: ${error.message}")
        }
    }

    private fun reportEncodingError(error: Exception, timestampMs: Long, session: Long) {
        if (isActive(session) && timestampMs - lastEncodingErrorTime >= ERROR_INTERVAL_MS) {
            lastEncodingErrorTime = timestampMs
            onStatus("Video: error comprimiendo frame ${error.message}")
        }
    }

    private companion object {
        private const val VIDEO_FRAME_INTERVAL_MS = 100L
        private const val DETECTION_FRAME_INTERVAL_MS = 140L
        private const val ERROR_INTERVAL_MS = 3000L

        private fun namedThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, name)
        }
    }
}