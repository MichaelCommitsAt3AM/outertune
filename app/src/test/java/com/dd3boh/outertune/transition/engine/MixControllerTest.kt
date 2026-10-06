package com.dd3boh.outertune.transition.engine

import com.dd3boh.outertune.transition.model.TransitionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MixControllerTest {

    private fun grid(bpm: Double, beats: Int = 400) = List(beats) { it * 60.0 / bpm }

    /** A at 120 BPM exits at beat 32 (16 s); B enters at its beat 8; a 16-beat zone. */
    private fun plan(bpmB: Double = 120.0, gridScalar: Double = 1.0) = TransitionPlan(
        initialSpeedB = 1.0,
        gridScalarB = gridScalar,
        anchorBeatA = 32.0,
        anchorBeatB = 8.0,
        transitionDurationBeats = 16.0,
        exitPointMs = 16_000,
        entryPointMs = (8 * 60_000 / bpmB).toLong(),
        durationMs = 8_000,
        gridA = grid(120.0),
        gridB = grid(bpmB),
    )

    @Test
    fun `B target follows A beat for beat`() {
        val c = MixController(plan(), 1.0)
        assertEquals(4_000, c.targetPositionBMs(16_000)) // anchor -> B beat 8
        assertEquals(2_500, c.targetPositionBMs(14_500)) // 3 beats before
    }

    @Test
    fun `interval matched B advances one beat per two of A`() {
        val c = MixController(plan(bpmB = 60.0, gridScalar = 2.0), 1.0)
        // 4 A beats after the anchor -> 2 B beats after B's anchor (beat 10 at 60 BPM = 10 s)
        assertEquals(10_000, c.targetPositionBMs(18_000))
    }

    @Test
    fun `stages across the zone`() {
        val c = MixController(plan(), 1.0)

        val preroll = c.step(0, positionAMs = 14_000, positionBMs = c.targetPositionBMs(14_000), incomingPlaying = true)
        assertEquals(MixController.Stage.PREROLL, preroll.stage)
        assertEquals(-4f / 16f, preroll.progress, 1e-4f)
        assertEquals(0.0, preroll.phaseErrorBeats, 1e-6)

        val middle = c.step(1_000, positionAMs = 20_000, positionBMs = c.targetPositionBMs(20_000), incomingPlaying = true)
        assertEquals(MixController.Stage.CROSSFADE, middle.stage)
        assertEquals(0.5f, middle.progress, 1e-4f)

        val done = c.step(2_000, positionAMs = 24_100, positionBMs = c.targetPositionBMs(24_100), incomingPlaying = true)
        assertEquals(MixController.Stage.DONE, done.stage)
    }

    @Test
    fun `a far-off incoming deck is re-seeked during preroll`() {
        val c = MixController(plan(), 1.0)
        val target = c.targetPositionBMs(13_000)
        val frame = c.step(0, positionAMs = 13_000, positionBMs = target - 600, incomingPlaying = true)
        assertNotNull(frame.reseekBMs)
        assertEquals(c.targetPositionBMs(13_000 + MixTuning.SEEK_LEAD_MS), frame.reseekBMs)
        assertNull(frame.speedB)
    }

    @Test
    fun `no re-seek while the incoming deck is still buffering`() {
        val c = MixController(plan(), 1.0)
        val target = c.targetPositionBMs(13_000)
        val frame = c.step(0, positionAMs = 13_000, positionBMs = target - 600, incomingPlaying = false)
        assertNull(frame.reseekBMs)
    }

    @Test
    fun `a B that lags speeds up`() {
        val c = MixController(plan(), 1.0)
        val target = c.targetPositionBMs(20_000)
        val frame = c.step(0, positionAMs = 20_000, positionBMs = target - 50, incomingPlaying = true)
        assertEquals(MixController.Stage.CROSSFADE, frame.stage)
        assert(frame.speedB!! > 1.0)
    }

    /** B enters on its first beat, which comes 0.2 s into the song. */
    private fun planEnteringAtStartOfB() = plan().copy(
        anchorBeatB = 0.0,
        entryPointMs = 200,
        gridB = grid(120.0).map { it + 0.2 },
    )

    @Test
    fun `B entering near its start waits until A reaches that point`() {
        val c = MixController(planEnteringAtStartOfB(), 1.0)
        // B's time 0 is 0.4 beats before its first beat, so it starts 0.2 s before A's anchor (16 s).
        assert(c.isBeforeStartOfB(13_000))
        assert(c.isBeforeStartOfB(15_700))
        assert(!c.isBeforeStartOfB(15_900))
        assertEquals(0, c.targetPositionBMs(13_000))
        assertEquals(200, c.targetPositionBMs(16_000))
    }

    @Test
    fun `B entering mid-song never waits`() {
        val c = MixController(plan(), 1.0)
        assert(!c.isBeforeStartOfB(13_000))
    }

    @Test
    fun `a waiting B is not corrected`() {
        val c = MixController(planEnteringAtStartOfB(), 1.0)
        val frame = c.step(0, positionAMs = 13_000, positionBMs = 0, incomingPlaying = false, incomingStarted = false)
        assertEquals(MixController.Stage.PREROLL, frame.stage)
        assertNull(frame.reseekBMs)
        assertNull(frame.speedB)
        assertEquals(0.0, frame.phaseErrorBeats, 0.0)
    }
}
