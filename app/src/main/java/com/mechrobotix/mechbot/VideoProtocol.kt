package com.mechrobotix.mechbot

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream

data class VideoFramePacket(
    val frameId: Long,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val detectionEnabled: Boolean,
    val detections: List<DetectionResult>,
    val jpeg: ByteArray
)

data class RemoteVideoFrame(
    val bitmap: Bitmap,
    val frameId: Long,
    val timestampMs: Long,
    val detectionEnabled: Boolean,
    val detections: List<DetectionResult>
)

object VideoProtocol {
    const val MAGIC = 0x4D424F54
    const val VERSION = 3
    const val MAX_JPEG_SIZE = 4_000_000
    const val MAX_DETECTIONS = 5

    fun write(output: DataOutputStream, packet: VideoFramePacket) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeLong(packet.frameId)
        output.writeLong(packet.timestampMs)
        output.writeInt(packet.width)
        output.writeInt(packet.height)
        output.writeInt(packet.rotationDegrees)
        output.writeBoolean(packet.mirrored)
        output.writeBoolean(packet.detectionEnabled)
        output.writeInt(packet.detections.size.coerceAtMost(MAX_DETECTIONS))
        packet.detections.take(MAX_DETECTIONS).forEach { detection ->
            output.writeInt(detection.trackingId)
            output.writeUTF(detection.label.take(96))
            output.writeFloat(detection.confidence)
            output.writeFloat(detection.left)
            output.writeFloat(detection.top)
            output.writeFloat(detection.right)
            output.writeFloat(detection.bottom)
            output.writeUTF(detection.proximity.name)
        }
        output.writeInt(packet.jpeg.size)
        output.write(packet.jpeg)
    }

    fun read(input: DataInputStream): VideoFramePacket {
        require(input.readInt() == MAGIC) { "Cabecera de video inválida" }
        require(input.readInt() == VERSION) { "Versión de video incompatible" }
        val frameId = input.readLong()
        val timestampMs = input.readLong()
        val width = input.readInt()
        val height = input.readInt()
        val rotationDegrees = input.readInt()
        val mirrored = input.readBoolean()
        val detectionEnabled = input.readBoolean()
        require(width in 1..4096 && height in 1..4096) { "Dimensiones de video inválidas" }
        require(rotationDegrees in listOf(0, 90, 180, 270)) { "Rotación de video inválida" }
        val detectionCount = input.readInt()
        require(detectionCount in 0..MAX_DETECTIONS) { "Cantidad de detecciones inválida" }
        val detections = ArrayList<DetectionResult>(detectionCount)
        repeat(detectionCount) {
            detections += DetectionResult(
                trackingId = input.readInt(),
                label = input.readUTF(),
                confidence = input.readFloat().coerceIn(0f, 1f),
                left = input.readFloat().coerceIn(0f, 1f),
                top = input.readFloat().coerceIn(0f, 1f),
                right = input.readFloat().coerceIn(0f, 1f),
                bottom = input.readFloat().coerceIn(0f, 1f),
                proximity = ObjectProximity.fromWire(input.readUTF())
            )
        }
        val jpegSize = input.readInt()
        require(jpegSize in 1..MAX_JPEG_SIZE) { "Tamaño de frame inválido" }
        val jpeg = ByteArray(jpegSize)
        input.readFully(jpeg)
        return VideoFramePacket(
            frameId,
            timestampMs,
            width,
            height,
            rotationDegrees,
            mirrored,
            detectionEnabled,
            detections,
            jpeg
        )
    }
}
