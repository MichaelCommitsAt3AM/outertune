package com.dd3boh.outertune.transition.math

import com.dd3boh.outertune.transition.model.TransitionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural lock for the pure transition math.
 *
 * These tests describe what calculatePlan() and the beat<->time helpers currently do, so that
 * the Phase 1+ refactor (persisting the full TransitionPlan and having playback read it verbatim)
 * can be verified to preserve the editor's math.
 */
class TransitionMathTest {

    /** Uniform beat grid: [start, start+interval, ...] with [count] entries (seconds). */
    private fun uniformGrid(count: Int, interval: Double, start: Double = 0.0): List<Double> =
        List(count) { start + it * interval }

    // --- beat <-> time round trip -------------------------------------------------------------

    @Test
    fun `beat to time is inverse of time to beat inside the grid`() {
        val grid = uniformGrid(200, 0.5)
        for (t in listOf(0.0, 0.25, 3.7, 51.9, 99.5)) {
            val beat = TransitionMath.getBeatForTimestamp(grid, t)
            val back = TransitionMath.getTimestampForBeat(grid, beat)
            assertEquals("round-trip for t=$t", t, back, 1e-6)
        }
    }

    @Test
    fun `time to beat on a 120 bpm grid yields two beats per second`() {
        val grid = uniformGrid(64, 0.5)
        assertEquals(0.0, TransitionMath.getBeatForTimestamp(grid, 0.0), 1e-9)
        assertEquals(2.0, TransitionMath.getBeatForTimestamp(grid, 1.0), 1e-9)
        assertEquals(7.4, TransitionMath.getBeatForTimestamp(grid, 3.7), 1e-9)
    }

    @Test
    fun `helpers extrapolate linearly outside the grid bounds`() {
        val grid = uniformGrid(10, 0.5, start = 1.0) // beats at 1.0 .. 5.5s
        // before the start
        assertEquals(0.5, TransitionMath.getTimestampForBeat(grid, -1.0), 1e-9)
        // after the end (last beat is index 9 at 5.5s)
        assertEquals(6.5, TransitionMath.getTimestampForBeat(grid, 11.0), 1e-9)
        assertEquals(11.0, TransitionMath.getBeatForTimestamp(grid, 6.5), 1e-9)
    }

    @Test
    fun `duplicate beats never produce NaN`() {
        val grid = listOf(0.0, 0.5, 0.5, 1.0, 1.5)
        for (t in listOf(0.5, 0.6, -1.0, 3.0)) {
            val beat = TransitionMath.getBeatForTimestamp(grid, t)
            assertTrue("t=$t gave $beat", beat.isFinite())
        }
        val flatEnd = listOf(0.0, 0.5, 1.0, 1.0)
        assertTrue(TransitionMath.getBeatForTimestamp(flatEnd, 2.0).isFinite())
    }

    @Test
    fun `duration is measured on the grid at the anchor, not from the first interval`() {
        // 120 BPM for the first 32 beats, then 128 BPM.
        val slow = List(32) { it * 0.5 }
        val fastInterval = 60.0 / 128.0
        val gridA = slow + List(96) { slow.last() + (it + 1) * fastInterval }
        val gridB = List(128) { it * 0.5 }

        val plan = TransitionMath.calculatePlan(
            gridA, gridB, 128f, 128f,
            offsetBeatsA = 40.0, offsetBeatsB = 8.0,
            config = TransitionConfig(barsCount = 4, widthFraction = 1.0f)
        )!!

        assertEquals((16 * fastInterval * 1000).toLong(), plan.durationMs)
    }

    @Test
    fun `empty grid helpers are total`() {
        assertEquals(0.0, TransitionMath.getBeatForTimestamp(emptyList(), 5.0), 0.0)
        assertEquals(0.0, TransitionMath.getTimestampForBeat(emptyList(), 5.0), 0.0)
    }

    // --- calculatePlan: guards ---------------------------------------------------------------

    @Test
    fun `calculatePlan returns null when either grid is empty`() {
        val grid = uniformGrid(32, 0.5)
        assertNull(
            TransitionMath.calculatePlan(emptyList(), grid, 120f, 120f, 0.0, 0.0, TransitionConfig())
        )
        assertNull(
            TransitionMath.calculatePlan(grid, emptyList(), 120f, 120f, 0.0, 0.0, TransitionConfig())
        )
    }

    // --- calculatePlan: tempo-sync mode (bpm diff <= 15) ------------------------------------

    @Test
    fun `close tempos use plain speed sync and no grid scaling`() {
        val gridA = uniformGrid(128, 0.5)
        val gridB = uniformGrid(128, 0.5)
        val config = TransitionConfig(barsCount = 4, widthFraction = 1.0f)

        val plan = TransitionMath.calculatePlan(
            gridA = gridA, gridB = gridB,
            bpmA = 120f, bpmB = 130f, // diff 10 <= 15 -> tempo sync
            offsetBeatsA = 8.0, offsetBeatsB = 4.0,
            config = config
        )
        assertNotNull(plan); plan!!

        assertEquals(1.0, plan.gridScalarB, 0.0)
        assertEquals((120f / 130f).toDouble(), plan.initialSpeedB, 1e-6)
        assertEquals(16.0, plan.transitionDurationBeats, 0.0)

        // widthFraction 1.0 => zero visual margin => anchor beat == offset beat
        assertEquals(8.0, plan.anchorBeatA, 1e-9)
        assertEquals(4.0, plan.anchorBeatB, 1e-9)

        // exit at gridA[8] = 4.0s, entry at gridB[4] = 2.0s
        assertEquals(4000L, plan.exitPointMs)
        assertEquals(2000L, plan.entryPointMs)

        // duration = 16 beats * intervalA(0.5s) = 8s
        assertEquals(8000L, plan.durationMs)
    }

    // --- calculatePlan: interval-match mode (bpm diff > 15) --------------------------------

    @Test
    fun `double-time partner is matched by grid scalar not by 2x speed`() {
        val intervalA = 60.0 / 140.0
        val intervalB = 60.0 / 70.0
        val gridA = uniformGrid(256, intervalA)
        val gridB = uniformGrid(256, intervalB)
        val config = TransitionConfig(barsCount = 8, widthFraction = 1.0f)

        val plan = TransitionMath.calculatePlan(
            gridA = gridA, gridB = gridB,
            bpmA = 140f, bpmB = 70f,
            offsetBeatsA = 16.0, offsetBeatsB = 8.0,
            config = config
        )
        assertNotNull(plan); plan!!

        // rawRatio = intervalB/intervalA = 2.0 -> best multiplier 2.0 -> speed ~ 1.0
        assertEquals(2.0, plan.gridScalarB, 1e-6)
        assertEquals(1.0, plan.initialSpeedB, 1e-6)

        // internal beat B = rawAnchorBeatB / gridScalar = 8.0 / 2.0
        assertEquals(4.0, plan.anchorBeatB, 1e-6)
        assertEquals(32.0, plan.transitionDurationBeats, 0.0) // 8 bars * 4
    }

    // --- calculatePlan: visual margin from widthFraction ----------------------------------

    @Test
    fun `narrower transition zone pushes the anchor further into the track`() {
        val gridA = uniformGrid(256, 0.5)
        val gridB = uniformGrid(256, 0.5)
        // widthFraction 0.5 => marginFraction 0.25 => visualMarginBeats = (0.25/0.5)*16 = 8
        val config = TransitionConfig(barsCount = 4, widthFraction = 0.5f)

        val plan = TransitionMath.calculatePlan(
            gridA, gridB, 120f, 120f,
            offsetBeatsA = 10.0, offsetBeatsB = 10.0,
            config = config
        )!!

        assertEquals(18.0, plan.anchorBeatA, 1e-9)
        assertEquals(18.0, plan.anchorBeatB, 1e-9)
    }

    @Test
    fun `plan carries the grids through unmodified`() {
        val gridA = uniformGrid(40, 0.5)
        val gridB = uniformGrid(40, 0.5)
        val plan = TransitionMath.calculatePlan(
            gridA, gridB, 120f, 120f, 4.0, 4.0, TransitionConfig(widthFraction = 1.0f)
        )!!
        assertEquals(gridA, plan.gridA)
        assertEquals(gridB, plan.gridB)
        assertTrue(plan.exitPointMs >= 0 && plan.entryPointMs >= 0)
    }
}
