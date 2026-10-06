package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for different playback implementations.
 * - SimplePlaybackEngine: Standard single-player playback.
 * - MixPlaybackEngine: Dual-player with transitions (DeckPair + TransitionRenderer).
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
     * Position (ms) within the song the user sees. In mix mode this is the incoming song once a
     * transition has passed its midpoint. Read on demand by the UI while it's visible.
     */
    fun logicalPositionMs(): Long

    /** Duration (ms) of the song the user sees; in mix mode, a song ends at its exit point. */
    fun logicalDurationMs(): Long

    /**
     * Seek to a position.
     * Engines may override this to handle logic (like canceling transitions).
     */
    fun seekTo(positionMs: Long)
}
