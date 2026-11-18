package com.mechrobotix.mechbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistedNavigatorTest {

    private class Sim {
        var now = 0L
        val moves = mutableListOf<MovementCommand>()
        val halts = mutableListOf<String>()
        val nav = AssistedNavigator(
            clock = { now },
            onMove = { moves += it },
            onHalt = { halts += it }
        )

        /** Avanza el reloj en pasos de 100 ms, enviando una lectura por paso. */
        fun run(ms: Long, reading: SpatialAnalysisResult = clear()) {
            var elapsed = 0L
            while (elapsed < ms) {
                now += 100L
                elapsed += 100L
                nav.update(reading)
                nav.tick()
            }
        }

        /** Avanza el reloj sin lecturas de cámara. */
        fun runWithoutReadings(ms: Long) {
            var elapsed = 0L
            while (elapsed < ms) {
                now += 100L
                elapsed += 100L
                nav.tick()
            }
        }
    }

    companion object {
        private fun obstacle(
            zone: String,
            distance: Float,
            inCorridor: Boolean = true,
            critical: Boolean = false
        ) = ProcessedObstacle("Objeto", distance, 0f, zone, inCorridor, critical)

        private fun reading(
            vararg obstacles: ProcessedObstacle,
            action: NavigationAction = NavigationAction.AVANZAR
        ) = SpatialAnalysisResult(action, "Libre", 0f, false, allObstacles = obstacles.toList())

        private fun clear() = reading()
    }

    /** Un ciclo completo: obstáculo al frente -> girar -> camino libre -> pasar -> seguir. */
    private fun evasionCycle(sim: Sim) {
        sim.nav.update(reading(obstacle("Centro", 1.2f)))
        sim.run(500L)          // lecturas con el camino libre: confirma y gira un poco más
        sim.run(2000L)         // pasa junto al obstáculo
    }

    @Test
    fun rodeaUnObstaculoCentralYSigue() {
        val sim = Sim()
        sim.nav.start()

        // Derecha ocupada a 1.5 m (fuera del corredor): el lado izquierdo está más libre
        sim.nav.update(reading(obstacle("Centro", 1.2f), obstacle("Derecha", 1.5f, inCorridor = false)))
        assertEquals(AssistState.TURNING, sim.nav.state)
        assertEquals(listOf(MovementCommand.LEFT), sim.moves)

        // 3 lecturas libres (300 ms) + 300 ms de giro extra = 600 ms antes de empezar a pasar
        sim.run(700L)
        assertEquals(AssistState.PASSING, sim.nav.state)
        assertEquals(listOf(MovementCommand.LEFT, MovementCommand.FORWARD), sim.moves)

        sim.run(2000L)
        assertEquals(AssistState.CRUISING, sim.nav.state)
        assertTrue(sim.halts.isEmpty())
    }

    @Test
    fun nuncaGiraHaciaElLadoDelObstaculo() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(reading(obstacle("Izquierda", 1.2f)))
        assertEquals(listOf(MovementCommand.RIGHT), sim.moves)

        val sim2 = Sim()
        sim2.nav.start()
        sim2.nav.update(reading(obstacle("Derecha", 1.2f)))
        assertEquals(listOf(MovementCommand.LEFT), sim2.moves)
    }

    @Test
    fun seDetieneSiElObstaculoYaEstaDemasiadoCerca() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(reading(obstacle("Centro", 0.6f, critical = true)))
        assertTrue(sim.moves.isEmpty())
        assertEquals(1, sim.halts.size)
        assertEquals(AssistState.BLOCKED, sim.nav.state)
    }

    @Test
    fun seDetieneSiElMotorPideFrenar() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(reading(action = NavigationAction.FRENAR))
        assertTrue(sim.moves.isEmpty())
        assertEquals(1, sim.halts.size)
    }

    @Test
    fun seDetieneSiAmbosLadosEstanOcupados() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(
            reading(
                obstacle("Centro", 1.2f),
                obstacle("Izquierda", 0.8f, inCorridor = false),
                obstacle("Derecha", 0.8f, inCorridor = false)
            )
        )
        assertTrue(sim.moves.isEmpty())
        assertEquals(1, sim.halts.size)
    }

    @Test
    fun seDetieneSiAparecioAlgoDelLadoDelGiro() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(reading(obstacle("Centro", 1.2f)))
        assertEquals(AssistState.TURNING, sim.nav.state)

        val turnSide = sim.moves.first()
        val zone = if (turnSide == MovementCommand.LEFT) "Izquierda" else "Derecha"
        sim.nav.update(reading(obstacle("Centro", 1.2f), obstacle(zone, 0.8f, inCorridor = false)))
        assertEquals(1, sim.halts.size)
    }

    @Test
    fun seDetieneSiNoLograLiberarElCamino() {
        val sim = Sim()
        sim.nav.start()
        val blocked = reading(obstacle("Centro", 1.2f))
        sim.nav.update(blocked)
        assertEquals(AssistState.TURNING, sim.nav.state)

        sim.run(4000L, blocked)
        assertEquals(1, sim.halts.size)
        assertEquals(AssistState.BLOCKED, sim.nav.state)
    }

    @Test
    fun seDetieneSiLaCamaraDejaDeEntregarLecturas() {
        val sim = Sim()
        sim.nav.start()
        sim.runWithoutReadings(1000L)
        assertTrue(sim.halts.isEmpty())
        sim.runWithoutReadings(1000L)
        assertEquals(1, sim.halts.size)
    }

    @Test
    fun cancelarDejaEnReposoYNoEmiteComandos() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(reading(obstacle("Centro", 1.2f)))
        sim.nav.cancel()
        assertEquals(AssistState.IDLE, sim.nav.state)

        val movesBefore = sim.moves.size
        sim.run(5000L, reading(obstacle("Centro", 1.2f)))
        assertEquals(movesBefore, sim.moves.size)
        assertTrue(sim.halts.isEmpty())
    }

    @Test
    fun noActuaSiNoSeInicio() {
        val sim = Sim()
        sim.nav.update(reading(obstacle("Centro", 1.2f)))
        sim.nav.tick()
        assertTrue(sim.moves.isEmpty())
        assertTrue(sim.halts.isEmpty())
        assertEquals(AssistState.IDLE, sim.nav.state)
    }

    @Test
    fun ignoraObstaculosFueraDelCorredorOLejanos() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(reading(obstacle("Izquierda", 1.0f, inCorridor = false)))
        sim.nav.update(reading(obstacle("Centro", 2.0f)))
        assertTrue(sim.moves.isEmpty())
        assertTrue(sim.halts.isEmpty())
        assertEquals(AssistState.CRUISING, sim.nav.state)
    }

    @Test
    fun limitaLasEvasionesSeguidas() {
        val sim = Sim()
        sim.nav.start()
        repeat(3) { evasionCycle(sim) }
        assertTrue(sim.halts.isEmpty())

        sim.nav.update(reading(obstacle("Centro", 1.2f)))
        assertEquals(1, sim.halts.size)
        assertEquals(AssistState.BLOCKED, sim.nav.state)
    }

    @Test
    fun trasUnaParadaNoVuelveAMoverseSolo() {
        val sim = Sim()
        sim.nav.start()
        sim.nav.update(reading(obstacle("Centro", 0.6f, critical = true)))
        val movesBefore = sim.moves.size

        sim.run(5000L)
        assertEquals(movesBefore, sim.moves.size)
        assertEquals(AssistState.BLOCKED, sim.nav.state)
    }
}