package com.dd3boh.outertune.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * Manages two ExoPlayer instances (Deck A and Deck B) to enable seamless mixing.
 */
class DeckManager(
    private val context: Context,
    private val playerCreator: () -> ExoPlayer,
    private val onActiveDeckChanged: (ExoPlayer) -> Unit
) {
    private val TAG = "DeckManager"

    val playerA: ExoPlayer = playerCreator()
    // Deck B only created when needed, saving resources
    private val playerB: ExoPlayer by lazy {
        Log.d(TAG, "Creating Deck B for DJ mode")
        playerCreator() }

    var activeDeck: ExoPlayer = playerA
        private set
    var standbyDeck: ExoPlayer = playerB
        private set

    // --- NEW: Observable Speed States ---
    private val _activeDeckSpeed = MutableStateFlow(1f)
    val activeDeckSpeed = _activeDeckSpeed.asStateFlow()

    private val _standbyDeckSpeed = MutableStateFlow(1f)
    val standbyDeckSpeed = _standbyDeckSpeed.asStateFlow()
    // ------------------------------------

    private val mixerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fadeJob: Job? = null
    private var speedJob: Job? = null

    // Call this after the transition is complete
    private fun scheduleSpeedReset(durationMs: Long = 10000) {
        speedJob?.cancel()

        val player = activeDeck
        val startSpeed = player.playbackParameters.speed

        if (startSpeed == 1f) {
            _activeDeckSpeed.value = 1f // Ensure UI is synced
            return
        }

        Log.i(TAG, "Ramping BPM from $startSpeed to 1.0 over ${durationMs}ms")

        speedJob = mixerScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + durationMs

            while (isActive && System.currentTimeMillis() < endTime) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = elapsed.toFloat() / durationMs

                // Linear interpolation from startSpeed to 1f
                val newSpeed = startSpeed + (1f - startSpeed) * progress
                player.setPlaybackSpeed(newSpeed)

                // NEW: Update flow so UI sees the ramp down
                _activeDeckSpeed.value = newSpeed

                delay(100) // Update 10 times a second
            }
            player.setPlaybackSpeed(1f)
            _activeDeckSpeed.value = 1f // Final reset
        }
    }

    fun prepareNext(mediaItem: MediaItem, startPositionMs: Long, bpmConfig: Float?) {
        Log.d(TAG, "Preparing Standby Deck: ${mediaItem.mediaMetadata.title}")

        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.setMediaItem(mediaItem)
        standbyDeck.seekTo(startPositionMs)
        standbyDeck.volume = 0f

        // NEW: Apply speed and update the StateFlow
        val speed = bpmConfig ?: 1f
        standbyDeck.setPlaybackSpeed(speed)
        _standbyDeckSpeed.value = speed

        Log.d(TAG, "Standby Deck prepared with Speed Multiplier: $speed")

        standbyDeck.prepare()
    }

    fun startCrossfade(durationMs: Long) {
        if (fadeJob?.isActive == true) return

        Log.i(TAG, "Starting Crossfade: $durationMs ms")

        val outgoingPlayer = activeDeck
        val incomingPlayer = standbyDeck

        incomingPlayer.play()

        fadeJob = mixerScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + durationMs

            while (isActive && System.currentTimeMillis() < endTime) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = elapsed.toFloat() / durationMs

                outgoingPlayer.volume = max(0f, 1f - progress)
                incomingPlayer.volume = max(0f, progress)

                delay(16) // 60 FPS
            }

            completeTransition()
        }
    }

    private fun completeTransition() {
        Log.i(TAG, "Transition Complete")

        // Swap decks FIRST
        val temp = activeDeck
        activeDeck = standbyDeck
        standbyDeck = temp

        // NEW: Swap Speed States so Active keeps its speed and Standby resets
        val prevStandbySpeed = _standbyDeckSpeed.value
        _activeDeckSpeed.value = prevStandbySpeed
        _standbyDeckSpeed.value = 1f // Reset standby for next song

        // Notify listener that the active player has changed
        onActiveDeckChanged(activeDeck)

        // Schedule the speed reset for the NEW active deck
        scheduleSpeedReset()

        // Clean up old deck
        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.volume = 1f
        standbyDeck.setPlaybackSpeed(1f)

        // Ensure active is full volume
        activeDeck.volume = 1f
    }

    fun cancelCrossfade() {
        fadeJob?.cancel()
        activeDeck.volume = 1f
        standbyDeck.volume = 0f
        standbyDeck.pause()
    }

    fun release() {
        mixerScope.cancel()
        playerA.release()
        playerB.release()
    }

    fun addListener(listener: Player.Listener) {
        playerA.addListener(listener)
        playerB.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        playerA.removeListener(listener)
        playerB.removeListener(listener)
    }
}