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
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

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
    fun create(handleAudioFocus: Boolean): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                pcmEncodingRestrictionLifted: Boolean,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            SilenceSkippingAudioProcessor(),
                            SonicAudioProcessor(),
                        )
                    )
                    .build()
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
            .also(onPlayerCreated)
    }

    companion object {
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
