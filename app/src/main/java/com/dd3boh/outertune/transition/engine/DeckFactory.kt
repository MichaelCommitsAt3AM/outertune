package com.dd3boh.outertune.transition.engine

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.nio.ByteBuffer
import java.util.WeakHashMap

/**
 * Builds the players used for mixing, the same way for the editor preview and playlist playback.
 *
 * Mix decks never use offload or float output: both bypass the audio processors, and so the
 * time-stretcher that beatmatching relies on.
 *
 * @param extensionRendererMode the user's decoder preference
 * @param onPlayerCreated called for each new player, to attach listeners
 */
class DeckFactory(
    private val context: Context,
    private val dataSourceFactory: () -> DataSource.Factory,
    private val extensionRendererMode: Int = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
    private val onPlayerCreated: (ExoPlayer) -> Unit = {},
) {
    private val processors = WeakHashMap<ExoPlayer, DeckAudioProcessor>()

    /** The transition processor in [player]'s audio chain, if it was built here. */
    fun processorFor(player: ExoPlayer): DeckAudioProcessor? = processors[player]

    fun create(handleAudioFocus: Boolean): ExoPlayer {
        val processor = DeckAudioProcessor()
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                pcmEncodingRestrictionLifted: Boolean,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                    .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        // The transition processor goes first, ahead of the time-stretcher, so it
                        // works in the song's own timeline.
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            arrayOf(processor),
                            SilenceSkippingAudioProcessor(),
                            SonicAudioProcessor(),
                        )
                    )
                    .build()
                return TimestampingAudioSink(sink, processor)
            }
        }.apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(extensionRendererMode)
        }

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(AUDIO_ATTRIBUTES, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .also { player ->
                processors[player] = processor
                onPlayerCreated(player)
            }
    }

    /**
     * Tells the [processor] the media time of each new input buffer, which the processor chain
     * itself never learns. The sink only takes a new buffer once the previous one is fully
     * consumed, so the time applies to the processor's very next input frame.
     *
     * Buffer timestamps carry the renderer's stream offset on top of the media time; it is
     * subtracted here so the processor sees the position within the song.
     */
    private class TimestampingAudioSink(
        sink: AudioSink,
        private val processor: DeckAudioProcessor,
    ) : ForwardingAudioSink(sink) {
        private var currentBuffer: ByteBuffer? = null
        private var streamOffsetUs = 0L

        override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
            streamOffsetUs = outputStreamOffsetUs
            super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            if (buffer !== currentBuffer) {
                currentBuffer = buffer
                processor.onInputBufferStart(presentationTimeUs - streamOffsetUs)
            }
            val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            // The same ByteBuffer object can come back later carrying new data.
            if (handled) currentBuffer = null
            return handled
        }

        override fun flush() {
            currentBuffer = null
            super.flush()
        }
    }

    companion object {
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
