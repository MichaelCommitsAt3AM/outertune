package com.dd3boh.outertune.transition.engine

import com.dd3boh.outertune.transition.model.Deck
import com.dd3boh.outertune.transition.model.EffectMode
import com.dd3boh.outertune.transition.model.EqMode
import com.dd3boh.outertune.transition.model.OverlapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionMixerTest {

    private fun state(
        deck: Deck,
        p: Float,
        vol: OverlapMode = OverlapMode.OVERLAP,
        eq: EqMode = EqMode.NONE,
        fx: EffectMode = EffectMode.NONE,
    ) = TransitionMixer.getMixState(deck, p, vol, eq, fx)

    @Test
    fun `legacy stored mode names parse regardless of casing`() {
        assertEquals(EffectMode.LOW_PASS_IN, EffectMode.fromStored("Low pass in"))
        assertEquals(EffectMode.LOW_PASS_OUT, EffectMode.fromStored("Low Pass out"))
        assertEquals(EffectMode.HIGH_PASS_OUT, EffectMode.fromStored("High Pass Out"))
        assertEquals(EqMode.CENTRE_BASS_SWAP, EqMode.fromStored("Centre Bass swap"))
        assertEquals(OverlapMode.DYNAMIC_SIDECHAIN, OverlapMode.fromStored("Dynamic Sidechain"))
        assertEquals(OverlapMode.CROSSFADE, OverlapMode.fromStored("CROSSFADE"))
        assertEquals(OverlapMode.OVERLAP, OverlapMode.fromStored("something unknown"))
        assertEquals(EffectMode.NONE, EffectMode.fromStored(null))
    }

    @Test
    fun `low pass out closes track A in the second half`() {
        assertEquals(1f, state(Deck.A, 0.25f, fx = EffectMode.LOW_PASS_OUT).filterHigh, 1e-6f)
        assertEquals(0.5f, state(Deck.A, 0.75f, fx = EffectMode.LOW_PASS_OUT).filterHigh, 1e-6f)
        assertEquals(1f, state(Deck.B, 0.75f, fx = EffectMode.LOW_PASS_OUT).filterHigh, 1e-6f)
    }

    @Test
    fun `crossfade keeps constant power`() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val a = state(Deck.A, p, vol = OverlapMode.CROSSFADE).volume
            val b = state(Deck.B, p, vol = OverlapMode.CROSSFADE).volume
            assertEquals("p=$p", 1f, a * a + b * b, 1e-5f)
        }
        assertEquals(1f, state(Deck.A, 0f, vol = OverlapMode.CROSSFADE).volume, 1e-6f)
        assertEquals(1f, state(Deck.B, 1f, vol = OverlapMode.CROSSFADE).volume, 1e-6f)
    }

    @Test
    fun `bass swap modes hand the bass over`() {
        assertEquals(1f, state(Deck.A, 0.2f, eq = EqMode.CENTRE_BASS_SWAP).bass, 1e-6f)
        assertEquals(0f, state(Deck.B, 0.2f, eq = EqMode.CENTRE_BASS_SWAP).bass, 1e-6f)
        assertEquals(0f, state(Deck.A, 0.8f, eq = EqMode.CENTRE_BASS_SWAP).bass, 1e-6f)
        assertEquals(1f, state(Deck.B, 0.8f, eq = EqMode.CENTRE_BASS_SWAP).bass, 1e-6f)
    }

    @Test
    fun `sidechain ducks A on B's beats in B's own timeline`() {
        val gridA = List(64) { 0.25 + it * 0.5 } // A's beats are offset from B's in media time
        val gridB = List(64) { 10.0 + it * 0.5 }

        // B is exactly on one of its beats; A is between its own beats.
        val onBeat = TransitionMixer.getMixState(
            Deck.A, 0.5f, OverlapMode.DYNAMIC_SIDECHAIN, EqMode.NONE, EffectMode.NONE,
            positionA = 3.5, positionB = 12.0, beatGridA = gridA, beatGridB = gridB
        )
        assertTrue(onBeat.sidechainActive)
        assertEquals(0.2f, onBeat.volume, 1e-4f)

        // Well after B's beat: no ducking.
        val offBeat = TransitionMixer.getMixState(
            Deck.A, 0.5f, OverlapMode.DYNAMIC_SIDECHAIN, EqMode.NONE, EffectMode.NONE,
            positionA = 3.5, positionB = 12.3, beatGridA = gridA, beatGridB = gridB
        )
        assertFalse(offBeat.sidechainActive)
        assertEquals(1f, offBeat.volume, 1e-6f)
    }
}
