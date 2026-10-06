package com.dd3boh.outertune.transition.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.dd3boh.outertune.transition.model.EffectMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Applies a [DeckAutomation] to a deck's audio: gain, a low-shelf bass cut and a low/high-pass
 * filter sweep, as functions of the deck's own media time.
 *
 * It sits first in the deck's processor chain, before the time-stretcher, so its input is in the
 * song's own timeline; [onInputBufferStart] (called by the deck's sink for every new buffer)
 * anchors that timeline, and frames are counted from there. Changes are made per small block,
 * with the gain ramped across each block, so nothing clicks.
 *
 * [automation] is written on the main thread and read on the playback thread.
 */
class DeckAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var automation: DeckAutomation? = null

    private var sampleRate = 0
    private var channels = 0
    private var isFloat = false

    // Media time of the next input frame
    private var anchorUs = C.TIME_UNSET
    private var framesSinceAnchor = 0L

    private var bassFilter: Biquad? = null
    private var toneFilter: Biquad? = null
    private var bassGainDb = 0.0
    private var toneCutoffHz = 0.0
    private var toneMode: EffectMode = EffectMode.NONE
    private var gain = 1f

    /** One block of zeros, for silent blocks. */
    private var silence = ByteArray(0)

    /** The sink is about to hand over a buffer whose first frame is at [presentationTimeUs]. */
    fun onInputBufferStart(presentationTimeUs: Long) {
        anchorUs = presentationTimeUs
        framesSinceAnchor = 0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.channelCount <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        silence = ByteArray(BLOCK_FRAMES * (if (isFloat) 4 else 2) * channels)
        bassFilter = Biquad(channels)
        toneFilter = Biquad(channels)
        bassGainDb = 0.0
        toneCutoffHz = 0.0
        toneMode = EffectMode.NONE
        gain = 1f
        anchorUs = C.TIME_UNSET
        framesSinceAnchor = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val bytesPerFrame = (if (isFloat) 4 else 2) * channels
        val frames = remaining / bytesPerFrame
        val auto = automation

        if (auto == null && gain == 1f && bassGainDb == 0.0 && toneMode == EffectMode.NONE) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.nativeOrder())
            var done = 0
            while (done < frames) {
                val block = minOf(BLOCK_FRAMES, frames - done)
                val bytes = block * bytesPerFrame
                val state = stateAt(auto, done)
                if (state.volume == 0f && gain == 0f) {
                    // Silent (B before the zone, A after it): skip the filters, which catch up
                    // when the gain ramps up again.
                    inputBuffer.position(inputBuffer.position() + bytes)
                    output.put(silence, 0, bytes)
                } else {
                    updateFilters(state, auto?.effectMode ?: EffectMode.NONE)
                    if (gain == 1f && state.volume == 1f && bassGainDb == 0.0 && toneMode == EffectMode.NONE) {
                        // Untouched: copy the block as is.
                        val limit = inputBuffer.limit()
                        inputBuffer.limit(inputBuffer.position() + bytes)
                        output.put(inputBuffer)
                        inputBuffer.limit(limit)
                    } else {
                        processBlock(inputBuffer, output, block, gain, state.volume)
                    }
                }
                gain = state.volume
                done += block
            }
        }
        framesSinceAnchor += frames
        output.flip()
    }

    private fun stateAt(auto: DeckAutomation?, frameOffset: Int): DeckState {
        if (auto == null || anchorUs == C.TIME_UNSET || sampleRate <= 0) return DeckState.NEUTRAL
        val timeSec = anchorUs / 1e6 + (framesSinceAnchor + frameOffset).toDouble() / sampleRate
        return auto.stateAt(timeSec)
    }

    /** Re-designs the filters only when their settings moved enough to hear. */
    private fun updateFilters(state: DeckState, effectMode: EffectMode) {
        val targetBassDb = if (state.bass >= 0.999f) 0.0 else 20 * log10(max(state.bass, BASS_FLOOR).toDouble())
        if (abs(targetBassDb - bassGainDb) > 0.25 || (targetBassDb == 0.0 && bassGainDb != 0.0)) {
            if (targetBassDb == 0.0) bassFilter?.reset()
            bassGainDb = targetBassDb
            if (bassGainDb != 0.0) bassFilter?.set(Biquad.lowShelf(sampleRate, BASS_CORNER_HZ, bassGainDb))
        }

        val open = state.filterHigh.toDouble()
        val mode = if (open >= 0.999) EffectMode.NONE else effectMode
        val cutoff = when {
            mode.isLowPass -> LP_OPEN_HZ * (LP_CLOSED_HZ / LP_OPEN_HZ).pow(1 - open)
            mode.isHighPass -> HP_OPEN_HZ * (HP_CLOSED_HZ / HP_OPEN_HZ).pow(1 - open)
            else -> 0.0
        }
        if (mode != toneMode || (cutoff > 0 && abs(cutoff / toneCutoffHz - 1) > 0.01)) {
            if (mode == EffectMode.NONE) toneFilter?.reset()
            toneMode = mode
            toneCutoffHz = cutoff
            when {
                mode.isLowPass -> toneFilter?.set(Biquad.lowPass(sampleRate, cutoff))
                mode.isHighPass -> toneFilter?.set(Biquad.highPass(sampleRate, cutoff))
            }
        }
    }

    private fun processBlock(input: ByteBuffer, output: ByteBuffer, frames: Int, fromGain: Float, toGain: Float) {
        val bass = if (bassGainDb != 0.0) bassFilter else null
        val tone = if (toneMode != EffectMode.NONE) toneFilter else null
        val step = (toGain - fromGain) / frames
        for (f in 0 until frames) {
            val g = fromGain + step * (f + 1)
            for (ch in 0 until channels) {
                var s = if (isFloat) input.float.toDouble() else input.short / 32768.0
                if (bass != null) s = bass.process(s, ch)
                if (tone != null) s = tone.process(s, ch)
                s *= g
                if (isFloat) output.putFloat(s.toFloat())
                else output.putShort((s * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
            }
        }
    }

    private companion object {
        /** About 3 ms at 44.1 kHz: the resolution of the automation. */
        const val BLOCK_FRAMES = 128

        const val BASS_CORNER_HZ = 180.0
        /** Strongest bass cut, about -26 dB. */
        const val BASS_FLOOR = 0.05f

        const val LP_OPEN_HZ = 20_000.0
        const val LP_CLOSED_HZ = 200.0
        const val HP_OPEN_HZ = 20.0
        const val HP_CLOSED_HZ = 2_500.0
    }
}
