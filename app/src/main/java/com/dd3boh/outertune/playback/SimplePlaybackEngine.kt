package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SimplePlaybackEngine(
    val player: ExoPlayer
) : PlaybackEngine {

    private val _activePlayer = MutableStateFlow(player)
    override val activePlayer: StateFlow<ExoPlayer> = _activePlayer.asStateFlow()

    override val isCrossfading = MutableStateFlow(false).asStateFlow()

    override fun start() {
        // No background polling needed for simple playback
    }

    override fun destroy() {
        player.release()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }
}
