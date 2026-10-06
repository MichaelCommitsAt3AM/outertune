package com.dd3boh.outertune.transition.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.dd3boh.outertune.transition.model.Deck
import com.dd3boh.outertune.transition.model.EffectMode
import com.dd3boh.outertune.transition.model.EqMode
import com.dd3boh.outertune.transition.model.OverlapMode
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Tests for the in-pipeline sound shaping: filters, automation curves and the processor. */
class DeckAudioTest {

    // --- Biquad ---------------------------------------------------------------------------------

    private fun gainDb(coefficients: Biquad.Coefficients, frequencyHz: Double, sampleRate: Int = 44_100): Double {
        val filter = Biquad(1).apply { set(coefficients) }
        var sumIn = 0.0
        var sumOut = 0.0
        for (n in 0 until sampleRate) {
            val x = sin(2 * PI * frequencyHz * n / sampleRate)
            val y = filter.process(x, 0)
            if (n > sampleRate / 4) { // skip the transient
                sumIn += x * x
                sumOut += y * y
            }
        }
        return 10 * log10(sumOut / sumIn)
    }

    @Test
    fun `low pass keeps the lows and removes the highs`() {
        val lp = Biquad.lowPass(44_100, 200.0)
        assertEquals(0.0, gainDb(lp, 40.0), 0.5)
        assertTrue(gainDb(lp, 5_000.0) < -40)
    }

    @Test
    fun `high pass removes the lows and keeps the highs`() {
        val hp = Biquad.highPass(44_100, 2_500.0)
        assertTrue(gainDb(hp, 100.0) < -50)
        assertEquals(0.0, gainDb(hp, 12_000.0), 0.5)
    }

    @Test
    fun `low shelf cuts only the bass`() {
        val shelf = Biquad.lowShelf(44_100, 180.0, -20.0)
        assertEquals(-20.0, gainDb(shelf, 30.0), 1.0)
        assertEquals(0.0, gainDb(shelf, 5_000.0), 0.5)
    }

    // --- DeckAutomation -------------------------------------------------------------------------

    private fun grid(bpm: Double) = List(400) { it * 60.0 / bpm }

    /** A exits at beat 32 (16 s) at 120 BPM; B enters at its beat 8 (4 s); a 16-beat zone (8 s). */
    private val plan = TransitionPlan(
        initialSpeedB = 1.0, gridScalarB = 1.0,
        anchorBeatA = 32.0, anchorBeatB = 8.0, transitionDurationBeats = 16.0,
        exitPointMs = 16_000, entryPointMs = 4_000, durationMs = 8_000,
        gridA = grid(120.0), gridB = grid(120.0),
    )

    @Test
    fun `automation follows each deck's own timeline`() {
        val config = TransitionConfig(overlapMode = OverlapMode.CROSSFADE, eqMode = EqMode.CENTRE_BASS_SWAP)
        val a = DeckAutomation(plan, config, Deck.A)
        val b = DeckAutomation(plan, config, Deck.B)

        // Before the zone: A untouched, B silent
        assertEquals(DeckState.NEUTRAL, a.stateAt(15.0))
        assertEquals(0f, b.stateAt(3.0).volume, 0f)

        // Midpoint is A at 20 s and B at 8 s: equal-power, so both at cos(45°)
        assertEquals(0.5, a.progressAt(20.0), 1e-9)
        assertEquals(0.5, b.progressAt(8.0), 1e-9)
        assertEquals(a.stateAt(20.0).volume, b.stateAt(8.0).volume, 1e-5f)

        // Bass swap: A keeps its bass early in the zone, B gets it late
        assertEquals(1f, a.stateAt(17.0).bass, 0f)
        assertEquals(0f, b.stateAt(5.0).bass, 0f)
        assertEquals(1f, b.stateAt(11.0).bass, 0f)

        // After the zone: A silent until the swap, B untouched
        assertEquals(0f, a.stateAt(24.5).volume, 0f)
        assertEquals(DeckState.NEUTRAL, b.stateAt(12.5))
    }

    @Test
    fun `interval matched B maps two A beats onto one of its own`() {
        val plan = plan.copy(gridScalarB = 2.0, gridB = grid(60.0), anchorBeatB = 4.0)
        val b = DeckAutomation(plan, TransitionConfig(), Deck.B)
        // 8 A beats (half the zone) = 4 B beats at 60 BPM = 4 s after B's anchor at 4 s
        assertEquals(0.5, b.progressAt(8.0), 1e-9)
    }

    // --- DeckAudioProcessor ---------------------------------------------------------------------

    private fun processor(automation: DeckAutomation?) = DeckAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
        this.automation = automation
    }

    private fun constantBuffer(frames: Int, value: Short): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * 2) { putShort(value) }
            flip()
        }

    private fun tailRms(output: ByteBuffer, lastFrames: Int): Double {
        val shorts = output.asShortBuffer()
        val start = shorts.limit() - lastFrames * 2
        var sum = 0.0
        for (i in start until shorts.limit()) sum += (shorts[i] / 32768.0).let { it * it }
        return sqrt(sum / (lastFrames * 2))
    }

    @Test
    fun `incoming deck is silent before the zone`() {
        val p = processor(DeckAutomation(plan, TransitionConfig(), Deck.B))
        p.onInputBufferStart(2_000_000) // B at 2 s, before its 4 s entry
        p.queueInput(constantBuffer(4_096, 16_000))
        assertEquals(0.0, tailRms(p.output, 1_024), 1e-6)
    }

    @Test
    fun `outgoing deck follows the crossfade by its media time`() {
        val automation = DeckAutomation(plan, TransitionConfig(overlapMode = OverlapMode.CROSSFADE), Deck.A)
        val p = processor(automation)
        p.onInputBufferStart(20_000_000) // A at the midpoint
        p.queueInput(constantBuffer(4_096, 16_000))
        // The last 1024 frames sit around 20.08 s, a little past the midpoint (where it'd be sin 45°).
        val expected = 16_000 / 32768.0 * automation.stateAt(20.0 + 3_584 / 44_100.0).volume
        assertEquals(expected, tailRms(p.output, 1_024), 0.002)
        assertTrue(expected < 16_000 / 32768.0 * sin(PI / 4))
    }

    @Test
    fun `no automation passes audio through untouched`() {
        val p = processor(null)
        p.onInputBufferStart(0)
        p.queueInput(constantBuffer(512, 12_345))
        val out = p.output.asShortBuffer()
        assertEquals(1_024, out.remaining())
        for (i in 0 until out.remaining()) assertEquals(12_345.toShort(), out[i])
    }

    @Test
    fun `low pass sweep only runs while the effect is active`() {
        val config = TransitionConfig(effectMode = EffectMode.LOW_PASS_IN)
        val b = DeckAutomation(plan, config, Deck.B)
        assertEquals(EffectMode.LOW_PASS_IN, b.effectMode)
        // Early in the zone B is mostly closed, at the end fully open
        assertTrue(b.stateAt(5.0).filterHigh < 0.2f)
        assertEquals(1f, b.stateAt(11.99).filterHigh, 0.01f)
    }
}
