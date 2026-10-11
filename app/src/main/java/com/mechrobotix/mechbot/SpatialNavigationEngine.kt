package com.mechrobotix.mechbot

import android.os.SystemClock
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

enum class NavigationAction {
    AVANZAR,
    RODEAR_IZQUIERDA,
    RODEAR_DERECHA,
    FRENAR
}

data class ProcessedObstacle(
    val label: String,
    val distanceMeters: Float,
    val closingSpeedMps: Float,
    val zone: String,
    val inCorridor: Boolean,
    val isCritical: Boolean
)

data class SpatialAnalysisResult(
    val action: NavigationAction,
    val primaryZone: String,
    val bottomYRatio: Float,
    val isCriticalDistance: Boolean,
    val distanceMeters: Float? = null,
    val closingSpeedMps: Float? = null,
    val label: String? = null,
    val allObstacles: List<ProcessedObstacle> = emptyList()
)

data class CameraGeometry(
    val heightMeters: Float = 0.60f,
    val pitchDownDegrees: Float = 20f, // Incrementado a 20° para corregir distancia sobreestimada
    val verticalFovDegrees: Float = 50f,
    val maxReliableMeters: Float = 5f
)

class SpatialNavigationEngine(var geometry: CameraGeometry = CameraGeometry()) {

    companion object {
        private const val CORRIDOR_LEFT = 0.28f
        private const val CORRIDOR_RIGHT = 0.72f
        private const val MIN_CORRIDOR_OVERLAP = 0.05f

        private const val LEFT_ZONE_END = 0.33f
        private const val RIGHT_ZONE_START = 0.66f

        // DISTANCIA DE FRENO ESTRICITA A 0.7 METROS
        private const val STOP_DISTANCE_M = 0.70f
        private const val WARN_DISTANCE_M = 1.80f
        private const val TTC_LIMIT_S = 1.2f
        private const val MIN_SPEED_FOR_TTC = 0.1f

        private const val CONFIRM_FRAMES = 1
        private const val BRAKE_HOLD_MS = 1000L // Mantiene el freno 1s ante pérdidas repentinas de detección
        private const val DIST_ALPHA = 0.5f
        private const val SPEED_ALPHA = 0.3f
        private const val TRACK_TIMEOUT_MS = 1200L

        // Si la caja está muy abajo en la imagen (proximidad ciega)
        private const val EDGE_TOUCH_Y = 0.90f

        private const val FALLBACK_VERY_CLOSE_M = 0.50f
        private const val FALLBACK_CLOSE_M = 1.00f
    }

    private class TrackState(var distance: Float, var lastTimeMs: Long, var speed: Float)

    private data class Candidate(
        val detection: DetectionResult,
        val distance: Float,
        val closingSpeed: Float,
        val inCorridor: Boolean,
        val touchesBottom: Boolean,
        val zone: String
    )

    private val tracks = HashMap<Int, TrackState>()
    private var criticalFrames = 0
    private var brakeUntilMs = 0L
    private var lastCriticalObstacle: ProcessedObstacle? = null

    fun reset() {
        tracks.clear()
        criticalFrames = 0
        brakeUntilMs = 0L
        lastCriticalObstacle = null
    }

    fun analyze(detections: List<DetectionResult>): SpatialAnalysisResult {
        val now = SystemClock.elapsedRealtime()
        val candidates = detections.mapNotNull { buildCandidate(it, now) }
        pruneTracks(now)

        val processedObstacles = candidates.map { c ->
            val ttc = if (c.closingSpeed > MIN_SPEED_FOR_TTC) c.distance / c.closingSpeed else null
            val isCrit = c.inCorridor && (
                    c.distance <= STOP_DISTANCE_M ||
                            c.touchesBottom ||
                            c.detection.proximity == ObjectProximity.VERY_CLOSE ||
                            (ttc != null && ttc < TTC_LIMIT_S && c.distance <= WARN_DISTANCE_M)
                    )
            ProcessedObstacle(
                label = c.detection.label,
                distanceMeters = c.distance,
                closingSpeedMps = c.closingSpeed,
                zone = c.zone,
                inCorridor = c.inCorridor,
                isCritical = isCrit
            )
        }

        // Evaluar si algún objeto actual es crítico
        val currentCritical = processedObstacles.firstOrNull { it.isCritical }

        if (currentCritical != null) {
            lastCriticalObstacle = currentCritical
            brakeUntilMs = now + BRAKE_HOLD_MS
        }

        // Si la detección desaparece pero estábamos frenando, mantener freno activo (Protección de proximidad ciega)
        val isStillHoldingBrake = now < brakeUntilMs

        if (processedObstacles.isEmpty()) {
            if (isStillHoldingBrake && lastCriticalObstacle != null) {
                val last = lastCriticalObstacle!!
                return SpatialAnalysisResult(
                    action = NavigationAction.FRENAR,
                    primaryZone = last.zone,
                    bottomYRatio = 1.0f,
                    isCriticalDistance = true,
                    distanceMeters = FALLBACK_VERY_CLOSE_M,
                    closingSpeedMps = 0f,
                    label = "${last.label} (Frente al chasis)",
                    allObstacles = listOf(
                        last.copy(distanceMeters = FALLBACK_VERY_CLOSE_M, isCritical = true)
                    )
                )
            }

            return SpatialAnalysisResult(
                action = NavigationAction.AVANZAR,
                primaryZone = "Libre",
                bottomYRatio = 0f,
                isCriticalDistance = false,
                allObstacles = emptyList()
            )
        }

        val leftObstacles = processedObstacles.filter { it.zone == "Izquierda" }
        val centerObstacles = processedObstacles.filter { it.zone == "Centro" || it.inCorridor }
        val rightObstacles = processedObstacles.filter { it.zone == "Derecha" }

        val hasCenterCritical = centerObstacles.any { it.isCritical || it.distanceMeters <= STOP_DISTANCE_M }
        val danger = hasCenterCritical || isStillHoldingBrake

        val minLeftDist = leftObstacles.minOfOrNull { it.distanceMeters } ?: 99f
        val minRightDist = rightObstacles.minOfOrNull { it.distanceMeters } ?: 99f

        val action = when {
            danger -> NavigationAction.FRENAR
            hasCenterCritical -> {
                if (minRightDist > minLeftDist) NavigationAction.RODEAR_DERECHA
                else NavigationAction.RODEAR_IZQUIERDA
            }
            else -> NavigationAction.AVANZAR
        }

        val primaryHazard = candidates.minByOrNull { it.distance }!!

        return SpatialAnalysisResult(
            action = action,
            primaryZone = primaryHazard.zone,
            bottomYRatio = primaryHazard.detection.bottom,
            isCriticalDistance = danger,
            distanceMeters = primaryHazard.distance,
            closingSpeedMps = primaryHazard.closingSpeed,
            label = primaryHazard.detection.label,
            allObstacles = processedObstacles
        )
    }

    private fun buildCandidate(d: DetectionResult, now: Long): Candidate? {
        val raw = groundDistance(d.bottom) ?: fallbackDistance(d.proximity) ?: return null

        val distance: Float
        val speed: Float
        if (d.trackingId >= 0) {
            val (dist, spd) = smooth(d.trackingId, raw, now)
            distance = dist
            speed = spd
        } else {
            distance = raw
            speed = 0f
        }

        val centerX = (d.left + d.right) / 2f
        val zone = when {
            centerX < LEFT_ZONE_END -> "Izquierda"
            centerX > RIGHT_ZONE_START -> "Derecha"
            else -> "Centro"
        }

        val overlap = min(d.right, CORRIDOR_RIGHT) - max(d.left, CORRIDOR_LEFT)
        return Candidate(
            detection = d,
            distance = distance,
            closingSpeed = speed,
            inCorridor = overlap >= MIN_CORRIDOR_OVERLAP,
            touchesBottom = d.bottom >= EDGE_TOUCH_Y,
            zone = zone
        )
    }

    private fun groundDistance(bottomY: Float): Float? {
        val vFov = Math.toRadians(geometry.verticalFovDegrees.toDouble())
        val pitch = Math.toRadians(geometry.pitchDownDegrees.toDouble())
        val offset = (bottomY.coerceIn(0f, 1f) - 0.5f) * 2f
        val angle = pitch + atan(offset * tan(vFov / 2.0))
        if (angle <= 0.02) return null
        val distance = (geometry.heightMeters / tan(angle)).toFloat()
        return if (distance > geometry.maxReliableMeters) null else distance
    }

    private fun fallbackDistance(proximity: ObjectProximity): Float? = when (proximity) {
        ObjectProximity.VERY_CLOSE -> FALLBACK_VERY_CLOSE_M
        ObjectProximity.CLOSE -> FALLBACK_CLOSE_M
        else -> null
    }

    private fun smooth(id: Int, raw: Float, now: Long): Pair<Float, Float> {
        val prev = tracks[id]
        if (prev == null) {
            tracks[id] = TrackState(raw, now, 0f)
            return raw to 0f
        }
        val dt = (now - prev.lastTimeMs) / 1000f
        if (dt <= 0f) return prev.distance to prev.speed

        val newDistance = prev.distance + DIST_ALPHA * (raw - prev.distance)
        val instantSpeed = (prev.distance - newDistance) / dt
        val newSpeed = prev.speed + SPEED_ALPHA * (instantSpeed - prev.speed)

        prev.distance = newDistance
        prev.speed = newSpeed
        prev.lastTimeMs = now
        return newDistance to newSpeed
    }

    private fun pruneTracks(now: Long) {
        tracks.entries.removeAll { now - it.value.lastTimeMs > TRACK_TIMEOUT_MS }
    }
}