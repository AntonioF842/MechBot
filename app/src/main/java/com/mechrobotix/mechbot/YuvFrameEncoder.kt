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
    fun copyFrame(image: ImageProxy, mirrored: Boolean): YuvFrameData = YuvFrameData(
        nv21 = yuv420ToNv21(image),
        width = image.width,
        height = image.height,
        rotationDegrees = image.imageInfo.rotationDegrees,
        mirrored = mirrored
    )

    fun encodeJpeg(frame: YuvFrameData, quality: Int = 54): ByteArray {
        val yuvImage = YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null)
        val output = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), quality, output)) {
            throw IllegalStateException("No se pudo comprimir el frame")
        }
        return output.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, nv21, 0, 1)
        copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, width / 2, height / 2, nv21, width * height, 2)
        copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, width / 2, height / 2, nv21, width * height + 1, 2)
        return nv21
    }

    private fun copyPlane(
        source: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        outputPixelStride: Int
    ) {
        val buffer = source.duplicate().apply { rewind() }
        var outputOffset = offset
        for (rowIndex in 0 until height) {
            val rowStart = rowIndex * rowStride
            for (columnIndex in 0 until width) {
                val sourceIndex = rowStart + columnIndex * pixelStride
                if (sourceIndex < buffer.limit() && outputOffset < output.size) {
                    output[outputOffset] = buffer.get(sourceIndex)
                }
                outputOffset += outputPixelStride
            }
        }
    }
}
