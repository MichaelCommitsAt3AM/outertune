package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionMixerTest {

    private fun state(track: String, p: Float, vol: String = "Overlap", eq: String = "None", fx: String = "None") =
        TransitionMixer.getMixState(track, p, vol, eq, fx)

    @Test
    fun `filter modes are recognised regardless of casing`() {
        for (mode in listOf("Low pass in", "Low Pass out")) assertTrue(mode, TransitionMixer.isLowPass(mode))
        for (mode in listOf("High Pass in", "High Pass Out")) assertTrue(mode, TransitionMixer.isHighPass(mode))
        assertFalse(TransitionMixer.isLowPass("High Pass in"))
        assertFalse(TransitionMixer.isHighPass("None"))
    }

    @Test
    fun `low pass out closes track A in the second half`() {
        assertEquals(1f, state("A", 0.25f, fx = "Low Pass out").filterHigh, 1e-6f)
        assertEquals(0.5f, state("A", 0.75f, fx = "Low Pass out").filterHigh, 1e-6f)
        assertEquals(1f, state("B", 0.75f, fx = "Low Pass out").filterHigh, 1e-6f)
    }

    @Test
    fun `crossfade keeps constant power`() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val a = state("A", p, vol = "Crossfade").volume
            val b = state("B", p, vol = "Crossfade").volume
            assertEquals("p=$p", 1f, a * a + b * b, 1e-5f)
        }
        assertEquals(1f, state("A", 0f, vol = "Crossfade").volume, 1e-6f)
        assertEquals(1f, state("B", 1f, vol = "Crossfade").volume, 1e-6f)
    }

    @Test
    fun `bass swap modes hand the bass over`() {
        assertEquals(1f, state("A", 0.2f, eq = "Centre Bass swap").bass, 1e-6f)
        assertEquals(0f, state("B", 0.2f, eq = "Centre Bass swap").bass, 1e-6f)
        assertEquals(0f, state("A", 0.8f, eq = "centre bass swap").bass, 1e-6f)
        assertEquals(1f, state("B", 0.8f, eq = "CENTRE BASS SWAP").bass, 1e-6f)
    }

    @Test
    fun `sidechain ducks A on B's beats in B's own timeline`() {
        val gridA = List(64) { 0.25 + it * 0.5 } // A's beats are offset from B's in media time
        val gridB = List(64) { 10.0 + it * 0.5 }

        // B is exactly on one of its beats; A is between its own beats.
        val onBeat = TransitionMixer.getMixState(
            "A", 0.5f, "Dynamic Sidechain", "None", "None",
            positionA = 3.5, positionB = 12.0, beatGridA = gridA, beatGridB = gridB
        )
        assertTrue(onBeat.sidechainActive)
        assertEquals(0.2f, onBeat.volume, 1e-4f)

        // Well after B's beat: no ducking.
        val offBeat = TransitionMixer.getMixState(
            "A", 0.5f, "Dynamic Sidechain", "None", "None",
            positionA = 3.5, positionB = 12.3, beatGridA = gridA, beatGridB = gridB
        )
        assertFalse(offBeat.sidechainActive)
        assertEquals(1f, offBeat.volume, 1e-6f)
    }
}
