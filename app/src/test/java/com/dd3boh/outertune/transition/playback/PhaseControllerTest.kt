package com.dd3boh.outertune.transition.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Closed-loop tests: a simulated Track B whose speed changes only take effect after the audio
 * pipeline's latency, observed through a jittery position, driven by [PhaseController] the same
 * way the decks drive it (target beat on B = anchor + elapsed beats of A / grid scalar).
 */
class PhaseControllerTest {

    private data class SimResult(val errorAtUnmute: Double, val maxCrossfadeError: Double, val reseeks: Int)

    /**
     * @param bpmA, bpmB tempos at 1x
     * @param gridScalar B beats per A beat are 1/gridScalar (interval match)
     * @param ratioError relative error in the plan's speed ratio (grid vs display BPM mismatch)
     * @param initialErrorBeats B's phase error when the preroll starts
     */
    private fun simulate(
        bpmA: Double,
        bpmB: Double,
        gridScalar: Double = 1.0,
        ratioError: Double = 0.003,
        initialErrorBeats: Double = 0.3,
        latencyMs: Long = 300,
        jitterMs: Double = 10.0,
        prerollSeconds: Double = 3.0,
        zoneBeats: Double = 16.0,
    ): SimResult {
        val rng = Random(42)
        val fA = bpmA / 60.0 // A beats per second
        val fB = bpmB / 60.0 // B beats per second at 1x
        val exactSpeed = fA / (gridScalar * fB)
        val controller = PhaseController(exactSpeed * (1 + ratioError))

        val dtMs = 20L
        // Speed changes waiting to reach the output: (effective-at ms, speed)
        val pending = ArrayDeque<Pair<Long, Double>>()
        var effectiveSpeed = controller.appliedSpeed

        val anchorA = prerollSeconds * fA // A beat where the zone starts
        val anchorB = 100.0
        var beatB = anchorB - (prerollSeconds * fA) / gridScalar - initialErrorBeats

        var errorAtUnmute = Double.NaN
        var maxCrossfadeError = 0.0
        var reseeks = 0
        var t = 0L
        while (true) {
            val beatA = fA * t / 1000.0
            val elapsedA = beatA - anchorA
            if (elapsedA / zoneBeats > 1.0) break

            while (pending.isNotEmpty() && pending.first().first <= t) effectiveSpeed = pending.removeFirst().second

            val observedB = beatB + rng.nextDouble(-jitterMs, jitterMs) / 1000.0 * fB
            val observedA = beatA + rng.nextDouble(-jitterMs, jitterMs) / 1000.0 * fA
            val target = anchorB + (observedA - anchorA) / gridScalar
            val error = target - observedB
            val stage = if (elapsedA < 0) PhaseController.Stage.PREROLL else PhaseController.Stage.CROSSFADE

            if (stage == PhaseController.Stage.PREROLL && controller.shouldReseek(error, -elapsedA, t)) {
                reseeks++
                beatB = anchorB + elapsedA / gridScalar // a seek lands on target (ignoring rebuffer time)
                pending.clear()
                effectiveSpeed = controller.appliedSpeed
            } else {
                controller.speedFor(stage, error, t)?.let { pending.addLast(t + latencyMs to it) }
            }

            if (stage == PhaseController.Stage.CROSSFADE) {
                val trueError = anchorB + elapsedA / gridScalar - beatB
                if (errorAtUnmute.isNaN()) errorAtUnmute = trueError
                maxCrossfadeError = maxOf(maxCrossfadeError, abs(PhaseController.wrapToNearestBeat(trueError)))
            }

            beatB += effectiveSpeed * fB * dtMs / 1000.0
            t += dtMs
        }
        return SimResult(errorAtUnmute, maxCrossfadeError, reseeks)
    }

    @Test
    fun `same tempo pair locks before B becomes audible`() {
        val r = simulate(bpmA = 124.0, bpmB = 124.0)
        assertTrue("unmute error ${r.errorAtUnmute}", abs(r.errorAtUnmute) < 0.06)
        assertTrue("crossfade error ${r.maxCrossfadeError}", r.maxCrossfadeError < 0.06)
    }

    @Test
    fun `pair a few BPM apart with a slightly wrong ratio stays locked`() {
        val r = simulate(bpmA = 128.0, bpmB = 120.0, ratioError = 0.005)
        assertTrue("unmute error ${r.errorAtUnmute}", abs(r.errorAtUnmute) < 0.06)
        assertTrue("crossfade error ${r.maxCrossfadeError}", r.maxCrossfadeError < 0.06)
    }

    @Test
    fun `interval matched 140 to 70 pair locks one B beat per two A beats`() {
        val r = simulate(bpmA = 140.0, bpmB = 70.0, gridScalar = 2.0)
        assertTrue("unmute error ${r.errorAtUnmute}", abs(r.errorAtUnmute) < 0.06)
        assertTrue("crossfade error ${r.maxCrossfadeError}", r.maxCrossfadeError < 0.06)
    }

    @Test
    fun `a large initial error is fixed with a re-seek rather than a long speed ramp`() {
        val r = simulate(bpmA = 124.0, bpmB = 124.0, initialErrorBeats = 1.3)
        assertEquals(1, r.reseeks)
        assertTrue("unmute error ${r.errorAtUnmute}", abs(r.errorAtUnmute) < 0.06)
    }

    @Test
    fun `re-seeks are capped and never happen right before unmute`() {
        val c = PhaseController(1.0)
        assertFalse(c.shouldReseek(phaseErrorBeats = 2.0, beatsUntilUnmute = 1.0, nowMs = 0))
        assertTrue(c.shouldReseek(2.0, 4.0, 0))
        assertTrue(c.shouldReseek(2.0, 4.0, 1000))
        assertFalse(c.shouldReseek(2.0, 4.0, 2000))
    }

    @Test
    fun `speed changes are rate limited and stay within the crossfade bound`() {
        val c = PhaseController(1.0)
        val first = c.speedFor(PhaseController.Stage.CROSSFADE, 0.4, nowMs = 0)
        assertEquals(1.0 + PhaseController.CROSSFADE_MAX_NUDGE, first!!, 1e-9)
        assertEquals(null, c.speedFor(PhaseController.Stage.CROSSFADE, -0.4, nowMs = 50))
    }

    @Test
    fun `crossfade error is wrapped to the nearest beat`() {
        assertEquals(0.2, PhaseController.wrapToNearestBeat(1.2), 1e-9)
        assertEquals(-0.3, PhaseController.wrapToNearestBeat(0.7), 1e-9)
        assertEquals(0.0, PhaseController.wrapToNearestBeat(-2.0), 1e-9)
    }
}
