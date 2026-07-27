package com.mechrobotix.mechbot

import kotlin.math.max
import kotlin.math.min

class DetectionSmoother {
    private var current: DetectionResult? = null
    private var lastSeenAt = 0L
    private var proximityCandidate: ObjectProximity? = null
    private var proximityCandidateCount = 0

    @Synchronized
    fun update(detections: List<DetectionResult>, timestampMs: Long): List<DetectionResult> {
        val detected = detections.firstOrNull()
        if (detected == null) {
            val retained = current
            if (retained != null && timestampMs - lastSeenAt <= RETENTION_MS) {
                return listOf(retained)
            }
            resetState()
            return emptyList()
        }
        val previous = current
        val sameObject: Boolean
        val next: DetectionResult
        if (previous != null && isSameObject(previous, detected)) {
            sameObject = true
            next = smooth(previous, detected)
        } else {
            sameObject = false
            next = detected
        }
        val measuredProximity = estimateProximity(next)
        val proximity = if (sameObject && previous != null) {
            stabilizeProximity(previous.proximity, measuredProximity)
        } else {
            proximityCandidate = null
            proximityCandidateCount = 0
            measuredProximity
        }
        current = next.copy(proximity = proximity)
        lastSeenAt = timestampMs
        return listOf(current!!)
    }

    @Synchronized
    fun reset() {
        resetState()
    }

    private fun smooth(previous: DetectionResult, detected: DetectionResult): DetectionResult {
        val alpha = if (
            previous.trackingId >= 0 &&
            detected.trackingId >= 0 &&
            previous.trackingId == detected.trackingId
        ) TRACKED_ALPHA else OVERLAP_ALPHA
        val label = when {
            detected.label != "Objeto" && previous.label == "Objeto" -> detected.label
            detected.label != "Objeto" && detected.confidence >= previous.confidence * 0.8f -> detected.label
            else -> previous.label
        }
        return DetectionResult(
            trackingId = if (detected.trackingId >= 0) detected.trackingId else previous.trackingId,
            label = label,
            confidence = lerp(previous.confidence, detected.confidence, CONFIDENCE_ALPHA),
            left = lerp(previous.left, detected.left, alpha),
            top = lerp(previous.top, detected.top, alpha),
            right = lerp(previous.right, detected.right, alpha),
            bottom = lerp(previous.bottom, detected.bottom, alpha),
            proximity = previous.proximity
        )
    }

    private fun stabilizeProximity(
        previous: ObjectProximity,
        measured: ObjectProximity
    ): ObjectProximity {
        if (measured == previous) {
            proximityCandidate = null
            proximityCandidateCount = 0
            return previous
        }
        if (measured == ObjectProximity.VERY_CLOSE) {
            proximityCandidate = null
            proximityCandidateCount = 0
            return measured
        }
        if (proximityCandidate == measured) {
            proximityCandidateCount++
        } else {
            proximityCandidate = measured
            proximityCandidateCount = 1
        }
        if (proximityCandidateCount >= PROXIMITY_CONFIRMATIONS) {
            proximityCandidate = null
            proximityCandidateCount = 0
            return measured
        }
        return previous
    }

    private fun isSameObject(previous: DetectionResult, detected: DetectionResult): Boolean {
        if (
            previous.trackingId >= 0 &&
            detected.trackingId >= 0 &&
            previous.trackingId == detected.trackingId
        ) return true
        return intersectionOverUnion(previous, detected) >= MINIMUM_IOU
    }

    private fun intersectionOverUnion(
        first: DetectionResult,
        second: DetectionResult
    ): Float {
        val intersectionLeft = max(first.left, second.left)
        val intersectionTop = max(first.top, second.top)
        val intersectionRight = min(first.right, second.right)
        val intersectionBottom = min(first.bottom, second.bottom)
        val intersectionWidth = (intersectionRight - intersectionLeft).coerceAtLeast(0f)
        val intersectionHeight = (intersectionBottom - intersectionTop).coerceAtLeast(0f)
        val intersectionArea = intersectionWidth * intersectionHeight
        val firstArea = (first.right - first.left).coerceAtLeast(0f) *
            (first.bottom - first.top).coerceAtLeast(0f)
        val secondArea = (second.right - second.left).coerceAtLeast(0f) *
            (second.bottom - second.top).coerceAtLeast(0f)
        val union = firstArea + secondArea - intersectionArea
        return if (union > 0f) intersectionArea / union else 0f
    }

    private fun estimateProximity(detection: DetectionResult): ObjectProximity {
        val widthRatio = (detection.right - detection.left).coerceAtLeast(0f)
        val heightRatio = (detection.bottom - detection.top).coerceAtLeast(0f)
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

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount

    private fun resetState() {
        current = null
        lastSeenAt = 0L
        proximityCandidate = null
        proximityCandidateCount = 0
    }

    private companion object {
        private const val RETENTION_MS = 650L
        private const val TRACKED_ALPHA = 0.42f
        private const val OVERLAP_ALPHA = 0.55f
        private const val CONFIDENCE_ALPHA = 0.35f
        private const val MINIMUM_IOU = 0.18f
        private const val PROXIMITY_CONFIRMATIONS = 2
    }
}
