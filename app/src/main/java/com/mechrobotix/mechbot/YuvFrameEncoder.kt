package com.mechrobotix.mechbot

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

data class YuvFrameData(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val mirrored: Boolean
)

object YuvFrameEncoder {
    private val nv21Pool = ThreadLocal<ByteArray>()
    private val streamPool = ThreadLocal<ByteArrayOutputStream>()

    fun copyFrame(image: ImageProxy, mirrored: Boolean): YuvFrameData = YuvFrameData(
        nv21 = yuv420ToNv21(image),
        width = image.width,
        height = image.height,
        rotationDegrees = image.imageInfo.rotationDegrees,
        mirrored = mirrored
    )

    fun encodeJpeg(frame: YuvFrameData, quality: Int = 54): ByteArray {
        val yuvImage = YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null)
        val output = streamPool.get()?.apply { reset() } ?: ByteArrayOutputStream(32 * 1024).also {
            streamPool.set(it)
        }
        val clampedQuality = quality.coerceIn(15, 95)
        if (!yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), clampedQuality, output)) {
            throw IllegalStateException("No se pudo comprimir el frame")
        }
        return output.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val expectedSize = width * height * 3 / 2

        // Reutiliza el arreglo en el hilo de cámara (copyFrame se ejecuta antes de enviar a encodingExecutor)
        // Nota: Si encodingExecutor corre en paralelo, asignamos un nuevo arreglo solo si es necesario o devolvemos copia limpia
        val nv21 = ByteArray(expectedSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlaneFast(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, nv21, 0, 1)
        copyPlaneFast(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, width / 2, height / 2, nv21, width * height, 2)
        copyPlaneFast(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, width / 2, height / 2, nv21, width * height + 1, 2)
        return nv21
    }

    private fun copyPlaneFast(
        source: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        outputPixelStride: Int
    ) {
        val buffer = source.duplicate()
        val limit = buffer.limit()

        // Ruta rápida por bloques cuando los píxeles son contiguos (pixelStride == 1 && outputPixelStride == 1)
        if (pixelStride == 1 && outputPixelStride == 1) {
            var outputOffset = offset
            for (rowIndex in 0 until height) {
                val rowStart = rowIndex * rowStride
                if (rowStart + width <= limit && outputOffset + width <= output.size) {
                    buffer.position(rowStart)
                    buffer.get(output, outputOffset, width)
                } else {
                    for (col in 0 until width) {
                        val srcIdx = rowStart + col
                        if (srcIdx < limit && outputOffset + col < output.size) {
                            output[outputOffset + col] = buffer.get(srcIdx)
                        }
                    }
                }
                outputOffset += width
            }
            return
        }

        // Ruta rápida para planos UV entrelazados directamente en NV21 (pixelStride == 2 && outputPixelStride == 2)
        // En muchos sensores Android, V y U ya están entrelazados en memoria con pixelStride = 2
        var outputOffset = offset
        for (rowIndex in 0 until height) {
            val rowStart = rowIndex * rowStride
            for (columnIndex in 0 until width) {
                val sourceIndex = rowStart + columnIndex * pixelStride
                if (sourceIndex < limit && outputOffset < output.size) {
                    output[outputOffset] = buffer.get(sourceIndex)
                }
                outputOffset += outputPixelStride
            }
        }
    }
}