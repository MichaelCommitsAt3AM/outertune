package com.dd3boh.outertune.transition.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * An AudioProcessor that fills the buffer with absolute silence (zeros)
 * when enabled, while keeping the stream active.
 *
 * This allows "hot-decking" (playing audio to keep the AudioTrack allocated)
 * without any audible bleed.
 */
class SilenceAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // No need to flush; immediate take-effect is desired
            }
        }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // We support any PCM format and don't change it.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val buffer = replaceOutputBuffer(remaining)

        if (isEnabled) {
            // Write zeros
            // We use a simple ByteArray for now; could be optimized with a cached zero buffer
            buffer.put(ByteArray(remaining))
            // Consume the input by advancing its position
            inputBuffer.position(inputBuffer.position() + remaining)
        } else {
            // Pass-through: Copy input to output
            buffer.put(inputBuffer)
        }

        buffer.flip()
    }
}
