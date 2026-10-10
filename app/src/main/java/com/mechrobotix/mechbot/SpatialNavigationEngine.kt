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

/**
 * Los tres primeros campos y el cuarto son los mismos que antes, así que el código existente
 * sigue compilando. Los nuevos tienen valor por defecto.
 */
data class SpatialAnalysisResult(
    val action: NavigationAction,
    val primaryZone: String,
    val bottomYRatio: Float,
    val isCriticalDistance: Boolean,
    val distanceMeters: Float? = null,
    val closingSpeedMps: Float? = null,
    val label: String? = null
)

/**
 * Geometría de la cámara respecto al piso. AJUSTAR con medidas reales de tu montaje.
 *
 * heightMeters:        altura del lente sobre el piso.
 * pitchDownDegrees:    cuánto está inclinada la cámara hacia abajo (0 = horizontal).
 * verticalFovDegrees:  campo de visión del eje VERTICAL de la imagen que analiza ML Kit
 *                      (depende de si el teléfono va en vertical u horizontal).
 * maxReliableMeters:   más allá de esto la estimación por piso no es confiable.
 */
data class CameraGeometry(
    val heightMeters: Float = 0.60f,
    val pitchDownDegrees: Float = 15f,
    val verticalFovDegrees: Float = 50f,
    val maxReliableMeters: Float = 6f
)

class SpatialNavigationEngine(var geometry: CameraGeometry = CameraGeometry()) {

    companion object {
        // Corredor central por donde va a pasar el robot/silla (fracción del ancho de imagen)
        private const val CORRIDOR_LEFT = 0.30f
        private const val CORRIDOR_RIGHT = 0.70f
        private const val MIN_CORRIDOR_OVERLAP = 0.05f

        // Sectores solo para mostrar en pantalla
        private const val LEFT_ZONE_END = 0.33f
        private const val RIGHT_ZONE_START = 0.66f

        // Umbrales de decisión (AJUSTAR según velocidad y distancia de frenado reales)
        private const val STOP_DISTANCE_M = 1.0f
        private const val WARN_DISTANCE_M = 2.0f
        private const val TTC_LIMIT_S = 1.5f
        private const val MIN_SPEED_FOR_TTC = 0.1f

        // Robustez frente a ruido
        private const val CONFIRM_FRAMES = 2
        private const val BRAKE_HOLD_MS = 600L
        private const val DIST_ALPHA = 0.4f
        private const val SPEED_ALPHA = 0.3f
        private const val TRACK_TIMEOUT_MS = 1000L

        // Si la caja toca el borde inferior, el objeto está cortado: probablemente muy cerca
        private const val EDGE_TOUCH_Y = 0.97f

        // Distancias de respaldo cuando no hay estimación por piso
        private const val FALLBACK_VERY_CLOSE_M = 0.8f
        private const val FALLBACK_CLOSE_M = 1.5f
    }

    private class TrackState(var distance: Float, var lastTimeMs: Long, var speed: Float)

    private data class Candidate(
        val detection: DetectionResult,
        val distance: Float,
        val closingSpeed: Float,
        val inCorridor: Boolean,
        val touchesBottom: Boolean
    )

    private val tracks = HashMap<Int, TrackState>()
    private var criticalFrames = 0
    private var brakeUntilMs = 0L

    /** Llamar al cambiar de cámara (frontal/trasera) o al reanudar. */
    fun reset() {
        tracks.clear()
        criticalFrames = 0
        brakeUntilMs = 0L
    }

    fun analyze(detections: List<DetectionResult>): SpatialAnalysisResult {
        val now = SystemClock.elapsedRealtime()
        val candidates = detections.mapNotNull { buildCandidate(it, now) }
        pruneTracks(now)

        // Prioridad: lo más cercano dentro del corredor; si no hay, lo más cercano en general
        val hazard = candidates.filter { it.inCorridor }.minByOrNull { it.distance }
            ?: candidates.minByOrNull { it.distance }

        if (hazard == null) {
            criticalFrames = 0
            val braking = now < brakeUntilMs
            return SpatialAnalysisResult(
                action = if (braking) NavigationAction.FRENAR else NavigationAction.AVANZAR,
                primaryZone = "Libre",
                bottomYRatio = 0f,
                isCriticalDistance = braking
            )
        }

        val det = hazard.detection
        val centerX = (det.left + det.right) / 2f
        val zone = when {
            centerX < LEFT_ZONE_END -> "Izquierda"
            centerX > RIGHT_ZONE_START -> "Derecha"
            else -> "Centro"
        }

        // Tiempo estimado al impacto (solo si realmente se está acercando)
        val ttc = if (hazard.closingSpeed > MIN_SPEED_FOR_TTC) {
            hazard.distance / hazard.closingSpeed
        } else null

        val danger = hazard.inCorridor && (
                hazard.distance <= STOP_DISTANCE_M ||
                        hazard.touchesBottom ||
                        (ttc != null && ttc < TTC_LIMIT_S && hazard.distance <= WARN_DISTANCE_M)
                )

        // Confirmar en varios fotogramas y mantener el freno un instante (evita parpadeo)
        criticalFrames = if (danger) criticalFrames + 1 else 0
        if (criticalFrames >= CONFIRM_FRAMES) brakeUntilMs = now + BRAKE_HOLD_MS
        val braking = now < brakeUntilMs

        val action = when {
            braking -> NavigationAction.FRENAR
            hazard.inCorridor && hazard.distance <= WARN_DISTANCE_M -> {
                // Rodear por el lado con más espacio libre
                val leftFree = det.left
                val rightFree = 1f - det.right
                if (rightFree > leftFree) NavigationAction.RODEAR_DERECHA
                else NavigationAction.RODEAR_IZQUIERDA
            }
            else -> NavigationAction.AVANZAR
        }

        return SpatialAnalysisResult(
            action = action,
            primaryZone = zone,
            bottomYRatio = det.bottom,
            isCriticalDistance = braking,
            distanceMeters = hazard.distance,
            closingSpeedMps = hazard.closingSpeed,
            label = det.label
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

        val overlap = min(d.right, CORRIDOR_RIGHT) - max(d.left, CORRIDOR_LEFT)
        return Candidate(
            detection = d,
            distance = distance,
            closingSpeed = speed,
            inCorridor = overlap >= MIN_CORRIDOR_OVERLAP,
            touchesBottom = d.bottom >= EDGE_TOUCH_Y
        )
    }

    /**
     * Distancia al punto donde el objeto toca el piso, con modelo de cámara estenopeica:
     *   ángulo bajo el horizonte = pitch + atan(offset * tan(vFov / 2))
     *   distancia = altura / tan(ángulo)
     * Devuelve null si el punto está sobre el horizonte o más lejos de lo confiable.
     */
    private fun groundDistance(bottomY: Float): Float? {
        val vFov = Math.toRadians(geometry.verticalFovDegrees.toDouble())
        val pitch = Math.toRadians(geometry.pitchDownDegrees.toDouble())
        val offset = (bottomY.coerceIn(0f, 1f) - 0.5f) * 2f // -1 (arriba) .. +1 (abajo)
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

    /** Suaviza la distancia por objeto y estima la velocidad de acercamiento (m/s, + = se acerca). */
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