package com.dd3boh.outertune.models

import androidx.compose.runtime.Immutable

@Immutable
data class LogicalPlayerState(
    /**
     * The metadata the UI should display.
     * During a transition, this will update to the NEXT song before the audio physically switches.
     */
    val activeMetadata: MediaMetadata? = null,

    /**
     * The logical playback position in milliseconds.
     * This will be clamped to the logical duration.
     */
    val currentPositionMs: Long = 0L,

    /**
     * The logical duration in milliseconds.
     * If a transition is set, this is the Exit Point. Otherwise, it is the real file duration.
     */
    val durationMs: Long = 1L,

    /**
     * Indicates if we are visually in the "Next Song" state while audio is crossfading.
     */
    val isTransitionActive: Boolean = false
) {
    // Helper for slider progress (0.0 -> 1.0)
    val progress: Float
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
}