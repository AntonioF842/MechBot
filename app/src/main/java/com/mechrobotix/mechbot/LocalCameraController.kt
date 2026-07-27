package com.mechrobotix.mechbot

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

class LocalCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onStatus: (String) -> Unit,
    private val onDetections: (List<DetectionResult>, Int, Int) -> Unit
) {
    private val cameraExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("mechbot-direct-camera"))
    private val detectionExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("mechbot-direct-detection"))
    private val detector = ObjectDetectionEngine(detectionExecutor)
    private val smoother = DetectionSmoother()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lastFrameTime = 0L
    private var lastDetectionErrorTime = 0L
    private var detectionReadyReported = false
    private val detectionBusy = AtomicBoolean(false)
    private val detectorClosed = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var detectionEnabled = true
    @Volatile private var detectionGeneration = 0L
    @Volatile private var sessionId = 0L

    @SuppressLint("MissingPermission")
    fun start(previewView: PreviewView) {
        if (closed || running) return
        running = true
        smoother.reset()
        lastFrameTime = 0L
        detectionReadyReported = false
        val session = ++sessionId
        onStatus("Iniciando cámara trasera.")
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!isActive(session)) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                previewView.post {
                    if (isActive(session)) bindCamera(provider, previewView, session)
                }
            } catch (error: Exception) {
                if (session == sessionId) running = false
                onStatus("No se pudo iniciar cámara: ${error.message}")
            }
        }, mainExecutor)
    }

    fun setDetectionEnabled(enabled: Boolean) {
        if (closed) return
        detectionEnabled = enabled
        detectionGeneration++
        smoother.reset()
        detectionReadyReported = false
        mainExecutor.execute { onDetections(emptyList(), 0, 0) }
        onStatus(if (enabled) "Detección habilitada." else "Detección deshabilitada.")
    }

    fun isDetectionEnabled(): Boolean = detectionEnabled

    @SuppressLint("MissingPermission")
    private fun bindCamera(
        provider: ProcessCameraProvider,
        previewView: PreviewView,
        session: Long
    ) {
        try {
            val useBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val selector = if (useBackCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    throw IllegalStateException("No hay cámara disponible")
                }
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val mirror = !useBackCamera
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(targetRotation)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis = analysis
            analysis.setAnalyzer(cameraExecutor) { image -> analyze(image, mirror, session) }
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            onStatus(if (useBackCamera) "Cámara trasera activa." else "Cámara frontal activa como respaldo.")
        } catch (error: Exception) {
            if (session == sessionId) running = false
            onStatus("No se pudo iniciar cámara: ${error.message}")
        }
    }

    private fun analyze(image: ImageProxy, mirror: Boolean, session: Long) {
        if (!isActive(session)) {
            image.close()
            return
        }
        if (!detectionEnabled) {
            image.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameTime < FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        if (!detectionBusy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameTime = now
        val generation = detectionGeneration
        val rotation = image.imageInfo.rotationDegrees
        val imageWidth = if (rotation == 90 || rotation == 270) image.height else image.width
        val imageHeight = if (rotation == 90 || rotation == 270) image.width else image.height
        try {
            detector.process(
                imageProxy = image,
                mirror = mirror,
                onResult = { rawDetections ->
                    if (isActive(session) && detectionEnabled && generation == detectionGeneration) {
                        val smoothed = smoother.update(rawDetections, SystemClock.elapsedRealtime())
                        publishDetections(smoothed, imageWidth, imageHeight, session, generation)
                        if (!detectionReadyReported) {
                            detectionReadyReported = true
                            onStatus("Detección activa.")
                        }
                    }
                },
                onFailure = { error ->
                    if (isActive(session) && detectionEnabled && generation == detectionGeneration) {
                        val retained = smoother.update(emptyList(), SystemClock.elapsedRealtime())
                        publishDetections(retained, imageWidth, imageHeight, session, generation)
                        reportDetectionError(error, now, session)
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
                val retained = smoother.update(emptyList(), SystemClock.elapsedRealtime())
                publishDetections(retained, imageWidth, imageHeight, session, generation)
                reportDetectionError(error, now, session)
            }
        }
    }

    fun stop() {
        val wasRunning = running
        running = false
        sessionId++
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        smoother.reset()
        lastFrameTime = 0L
        if (!closed) mainExecutor.execute { onDetections(emptyList(), 0, 0) }
        if (wasRunning && !closed) onStatus("Cámara detenida.")
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        cameraExecutor.shutdown()
        if (!detectionBusy.get()) closeDetectionResources()
    }

    private fun closeDetectionResources() {
        if (!detectorClosed.compareAndSet(false, true)) return
        detector.close()
        detectionExecutor.shutdown()
    }

    private fun publishDetections(
        detections: List<DetectionResult>,
        imageWidth: Int,
        imageHeight: Int,
        session: Long,
        generation: Long
    ) {
        mainExecutor.execute {
            if (isActive(session) && detectionEnabled && generation == detectionGeneration) {
                onDetections(detections, imageWidth, imageHeight)
            }
        }
    }

    private fun reportDetectionError(error: Exception, timestampMs: Long, session: Long) {
        if (isActive(session) && timestampMs - lastDetectionErrorTime >= ERROR_INTERVAL_MS) {
            lastDetectionErrorTime = timestampMs
            onStatus("Error de detección: ${error.message}")
        }
    }

    private fun isActive(session: Long): Boolean = running && session == sessionId

    private companion object {
        private const val FRAME_INTERVAL_MS = 130L
        private const val ERROR_INTERVAL_MS = 3000L

        private fun namedThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, name)
        }
    }
}
