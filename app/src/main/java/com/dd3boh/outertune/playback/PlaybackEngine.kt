package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for different playback implementations.
 * - SimplePlaybackEngine: Standard single-player playback.
 * - MixPlaybackEngine: Dual-player with transitions (DeckManager).
 */
interface PlaybackEngine {
    /**
     * The currently active ExoPlayer instance that the UI and MediaSession should listen to.
     * This may change during runtime (e.g. in Mix mode when swapping decks).
     */
    val activePlayer: StateFlow<ExoPlayer>

    /**
     * Whether a transition or crossfade is currently active.
     */
    val isCrossfading: StateFlow<Boolean>

    /**
     * Called when this engine is set as the active engine.
     * Start any polling loops or background tasks here.
     */
    fun start()

    /**
     * Called when this engine is replaced or destroyed.
     * Release all resources (Players, scopes, etc.).
     */
    fun destroy()

    /**
     * Seek to a position.
     * Engines may override this to handle logic (like canceling transitions).
     */
    fun seekTo(positionMs: Long)
}
