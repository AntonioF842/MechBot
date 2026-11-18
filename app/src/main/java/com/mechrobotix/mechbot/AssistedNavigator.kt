package com.mechrobotix.mechbot

enum class AssistState { IDLE, CRUISING, TURNING, PASSING, BLOCKED }

data class AssistConfig(
    val evadeTriggerM: Float = 1.5f,
    val clearM: Float = 2.2f,
    val sideMinM: Float = 1.0f,
    val clearConfirmFrames: Int = 3,
    val extraTurnMs: Long = 300L,
    val maxTurnMs: Long = 3500L,
    val passMs: Long = 1500L,
    val maxEvasions: Int = 3,
    val evasionWindowMs: Long = 15000L,
    val staleFeedMs: Long = 1500L
)

class AssistedNavigator(
    private val clock: () -> Long,
    private val onMove: (MovementCommand) -> Unit,
    private val onHalt: (String) -> Unit,
    private val onStateChanged: (AssistState, String) -> Unit = { _, _ -> },
    var config: AssistConfig = AssistConfig()
) {
    var state: AssistState = AssistState.IDLE
        private set

    private var lastFeedMs = 0L
    private var turnSide: MovementCommand = MovementCommand.STOP
    private var turnStartedMs = 0L
    private var passStartedMs = 0L
    private var clearFrames = 0
    private var extraTurnUntilMs = 0L
    private val evasionTimes = ArrayDeque<Long>()

    fun start() {
        evasionTimes.clear()
        resetManeuver()
        lastFeedMs = clock()
        setState(AssistState.CRUISING, "Asistencia activa")
    }

    fun cancel() {
        if (state == AssistState.IDLE) return
        resetManeuver()
        setState(AssistState.IDLE, "")
    }

    fun update(result: SpatialAnalysisResult) {
        if (state == AssistState.IDLE || state == AssistState.BLOCKED) return
        val now = clock()
        lastFeedMs = now
        val obstacles = result.allObstacles

        // Demasiado cerca: no se maniobra, se detiene
        if (result.action == NavigationAction.FRENAR || obstacles.any { it.isCritical }) {
            halt("Obstáculo muy cerca")
            return
        }

        when (state) {
            AssistState.CRUISING, AssistState.PASSING -> evaluateAhead(obstacles, now)
            AssistState.TURNING -> evaluateTurning(obstacles, now)
            else -> Unit
        }
    }

    fun tick() {
        if (state == AssistState.IDLE || state == AssistState.BLOCKED) return
        val now = clock()

        if (now - lastFeedMs > config.staleFeedMs) {
            halt("Cámara sin lecturas: asistencia detenida")
            return
        }

        when (state) {
            AssistState.TURNING -> {
                if (extraTurnUntilMs != 0L && now >= extraTurnUntilMs) {
                    startPass(now)
                } else if (now - turnStartedMs > config.maxTurnMs) {
                    halt("No pude rodear el obstáculo")
                }
            }
            AssistState.PASSING -> {
                if (now - passStartedMs >= config.passMs) {
                    setState(AssistState.CRUISING, "Camino libre")
                }
            }
            else -> Unit
        }
    }

    private fun evaluateAhead(obstacles: List<ProcessedObstacle>, now: Long) {
        val blocking = obstacles
            .filter { it.inCorridor && it.distanceMeters <= config.evadeTriggerM }
            .minByOrNull { it.distanceMeters } ?: return

        val side = chooseSide(blocking, obstacles)
        if (side == null) {
            halt("Sin paso libre para rodear")
            return
        }

        while (evasionTimes.isNotEmpty() && now - evasionTimes.first() > config.evasionWindowMs) {
            evasionTimes.removeFirst()
        }
        if (evasionTimes.size >= config.maxEvasions) {
            halt("Demasiados obstáculos seguidos: toma el control")
            return
        }
        evasionTimes.addLast(now)

        turnSide = side
        turnStartedMs = now
        clearFrames = 0
        extraTurnUntilMs = 0L
        setState(
            AssistState.TURNING,
            if (side == MovementCommand.LEFT) "Rodeando por la izquierda" else "Rodeando por la derecha"
        )
        onMove(side)
    }

    private fun evaluateTurning(obstacles: List<ProcessedObstacle>, now: Long) {
        // Apareció algo del lado hacia el que estamos girando
        val sideZone = if (turnSide == MovementCommand.LEFT) ZONE_LEFT else ZONE_RIGHT
        if (obstacles.any { it.zone == sideZone && it.distanceMeters <= config.sideMinM }) {
            halt("Lado ocupado: no puedo rodear")
            return
        }

        val corridorFree = obstacles.none { it.inCorridor && it.distanceMeters <= config.clearM }
        if (corridorFree) {
            clearFrames++
            if (clearFrames >= config.clearConfirmFrames && extraTurnUntilMs == 0L) {
                extraTurnUntilMs = now + config.extraTurnMs
            }
        } else {
            clearFrames = 0
            extraTurnUntilMs = 0L
        }
    }

    private fun startPass(now: Long) {
        passStartedMs = now
        clearFrames = 0
        extraTurnUntilMs = 0L
        setState(AssistState.PASSING, "Pasando el obstáculo")
        onMove(MovementCommand.FORWARD)
    }

    /** Elige el lado de giro. Nunca gira hacia el lado donde está el obstáculo que bloquea. */
    private fun chooseSide(blocking: ProcessedObstacle, obstacles: List<ProcessedObstacle>): MovementCommand? {
        val leftNear = nearest(ZONE_LEFT, obstacles)
        val rightNear = nearest(ZONE_RIGHT, obstacles)

        val canLeft = leftNear > config.sideMinM && blocking.zone != ZONE_LEFT
        val canRight = rightNear > config.sideMinM && blocking.zone != ZONE_RIGHT

        return when {
            canLeft && canRight -> if (leftNear > rightNear) MovementCommand.LEFT else MovementCommand.RIGHT
            canLeft -> MovementCommand.LEFT
            canRight -> MovementCommand.RIGHT
            else -> null
        }
    }

    private fun nearest(zone: String, obstacles: List<ProcessedObstacle>): Float =
        obstacles.filter { it.zone == zone }.minOfOrNull { it.distanceMeters } ?: Float.MAX_VALUE

    private fun halt(reason: String) {
        resetManeuver()
        setState(AssistState.BLOCKED, reason)
        onHalt(reason)
    }

    private fun resetManeuver() {
        turnSide = MovementCommand.STOP
        clearFrames = 0
        extraTurnUntilMs = 0L
    }

    private fun setState(newState: AssistState, message: String) {
        state = newState
        onStateChanged(newState, message)
    }

    private companion object {
        private const val ZONE_LEFT = "Izquierda"
        private const val ZONE_RIGHT = "Derecha"
    }
}