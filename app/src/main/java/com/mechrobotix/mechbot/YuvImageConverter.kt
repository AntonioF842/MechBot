package com.mechrobotix.mechbot

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object YuvImageConverter {
    fun imageProxyToJpeg(image: ImageProxy, quality: Int = 45): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
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
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        outputPixelStride: Int
    ) {
        val row = ByteArray(rowStride)
        var outputOffset = offset
        buffer.rewind()
        for (rowIndex in 0 until height) {
            val bytesPerRow = if (pixelStride == 1 && outputPixelStride == 1) width else rowStride
            val length = minOf(bytesPerRow, buffer.remaining())
            buffer.get(row, 0, length)
            var inputOffset = 0
            for (col in 0 until width) {
                if (outputOffset < output.size && inputOffset < length) {
                    output[outputOffset] = row[inputOffset]
                }
                outputOffset += outputPixelStride
                inputOffset += pixelStride
            }
            if (rowIndex < height - 1) {
                val skip = rowStride - length
                if (skip > 0 && buffer.remaining() >= skip) buffer.position(buffer.position() + skip)
            }
        }
    }
}
