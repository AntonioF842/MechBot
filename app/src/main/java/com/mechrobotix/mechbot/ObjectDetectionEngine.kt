package com.mechrobotix.mechbot

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executor
import kotlin.math.max

class ObjectDetectionEngine(
    private val executor: Executor
) {
    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .setExecutor(executor)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    fun process(
        imageProxy: ImageProxy,
        mirror: Boolean,
        onResult: (List<DetectionResult>) -> Unit,
        onFailure: (Exception) -> Unit,
        onComplete: () -> Unit
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onFailure(IllegalStateException("El frame no contiene una imagen válida"))
            onComplete()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val imageWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val imageHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(inputImage)
            .addOnSuccessListener(executor) { objects ->
                onResult(objects.map { it.toResult(imageWidth, imageHeight, mirror) })
            }
            .addOnFailureListener(executor) { error -> onFailure(error) }
            .addOnCompleteListener(executor) { onComplete() }
    }

    fun close() {
        detector.close()
    }

    private fun DetectedObject.toResult(
        imageWidth: Int,
        imageHeight: Int,
        mirror: Boolean
    ): DetectionResult {
        val selectedLabel = labels.maxByOrNull { it.confidence }
        val label = selectedLabel?.text?.let(::spanishLabel) ?: "Objeto"
        val confidence = selectedLabel?.confidence ?: 0f
        val bounds = boundingBox
        val rawLeft = bounds.left.toFloat().div(imageWidth).coerceIn(0f, 1f)
        val rawTop = bounds.top.toFloat().div(imageHeight).coerceIn(0f, 1f)
        val rawRight = bounds.right.toFloat().div(imageWidth).coerceIn(0f, 1f)
        val rawBottom = bounds.bottom.toFloat().div(imageHeight).coerceIn(0f, 1f)
        val left = if (mirror) 1f - rawRight else rawLeft
        val right = if (mirror) 1f - rawLeft else rawRight
        return DetectionResult(
            trackingId = trackingId ?: -1,
            label = label,
            confidence = confidence,
            left = left.coerceIn(0f, 1f),
            top = rawTop,
            right = right.coerceIn(0f, 1f),
            bottom = rawBottom,
            proximity = estimateProximity(left, rawTop, right, rawBottom)
        )
    }

    private fun spanishLabel(value: String): String = when (value.trim().lowercase()) {
        "fashion good", "fashion goods" -> "Objeto"
        "food" -> "Alimento"
        "home good", "home goods" -> "Artículo del hogar"
        "place", "places" -> "Lugar"
        "plant", "plants" -> "Planta"
        "unknown", "" -> "Objeto"
        else -> value
    }

    private fun estimateProximity(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): ObjectProximity {
        val widthRatio = (right - left).coerceAtLeast(0f)
        val heightRatio = (bottom - top).coerceAtLeast(0f)
        val areaRatio = widthRatio * heightRatio
        val largestDimension = max(widthRatio, heightRatio)
        return when {
            areaRatio >= 0.42f || largestDimension >= 0.82f -> ObjectProximity.VERY_CLOSE
            areaRatio >= 0.20f || largestDimension >= 0.62f -> ObjectProximity.CLOSE
            areaRatio >= 0.075f || largestDimension >= 0.40f -> ObjectProximity.MEDIUM
            areaRatio >= 0.025f || largestDimension >= 0.22f -> ObjectProximity.FAR
            else -> ObjectProximity.VERY_FAR
        }
    }
}
