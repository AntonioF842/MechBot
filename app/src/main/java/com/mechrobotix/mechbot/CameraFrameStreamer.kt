package com.mechrobotix.mechbot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraFrameStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onStatus: (String) -> Unit
) {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var sender: FrameSender? = null
    private var lastFrameTime = 0L

    @SuppressLint("MissingPermission")
    fun start(sender: FrameSender) {
        this.sender = sender
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime >= 150L) {
                            lastFrameTime = now
                            val jpeg = YuvImageConverter.imageProxyToJpeg(image, quality = 45)
                            sender.sendFrame(jpeg)
                        }
                    } catch (e: Exception) {
                        onStatus("Cámara: error frame ${e.message}")
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
                onStatus("Cámara frontal activa.")
            } catch (e: Exception) {
                onStatus("No se pudo iniciar cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        sender = null
        onStatus("Cámara detenida.")
    }
}
