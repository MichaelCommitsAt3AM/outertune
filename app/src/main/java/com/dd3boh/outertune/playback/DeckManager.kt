package com.dd3boh.outertune.playback

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.utils.DeckState
import com.dd3boh.outertune.utils.TransitionMixer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages two ExoPlayer instances (Deck A and Deck B) to enable seamless mixing.
 * Handles Volume Crossfading, Speed Ramping (Beatmatching), and EQ/Filter Effects.
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
        playerCreator()
    }

    // Audio Effects (Graphic Equalizer)
    private var eqA: Equalizer? = null
    private var eqB: Equalizer? = null

    var activeDeck: ExoPlayer = playerA
        private set
    var standbyDeck: ExoPlayer = playerB
        private set

    // --- Observable Speed States ---
    private val _activeDeckSpeed = MutableStateFlow(1f)
    val activeDeckSpeed = _activeDeckSpeed.asStateFlow()

    private val _standbyDeckSpeed = MutableStateFlow(1f)
    val standbyDeckSpeed = _standbyDeckSpeed.asStateFlow()
    // ------------------------------------

    private val mixerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fadeJob: Job? = null
    private var speedJob: Job? = null

    /**
     * Prepares the Standby Deck with the next track.
     * @param bpmConfig: Optional speed multiplier (e.g., 1.02x) for beatmatching.
     */
    fun prepareNext(mediaItem: MediaItem, startPositionMs: Long, bpmConfig: Float?) {
        Log.d(TAG, "Preparing Standby Deck: ${mediaItem.mediaMetadata.title}")

        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.setMediaItem(mediaItem)

        // IMPORTANT: Seek EXACTLY to the Entry Point defined in the editor
        standbyDeck.seekTo(startPositionMs)
        standbyDeck.volume = 0f

        // Apply speed and update Flow
        val speed = bpmConfig ?: 1f
        standbyDeck.setPlaybackSpeed(speed)
        _standbyDeckSpeed.value = speed

        Log.d(TAG, "Standby Deck prepared with Speed Multiplier: $speed")

        standbyDeck.prepare()

        // Initialize EQ for standby deck if needed
        ensureEq(standbyDeck)
    }

    /**
     * Executes the transition using the specific FX modes saved in the database.
     */
    fun startCrossfade(
        durationMs: Long,
        overlapMode: String = "Overlap",
        eqMode: String = "None",
        effectMode: String = "None"
    ) {
        if (fadeJob?.isActive == true) return

        Log.i(TAG, "Starting Crossfade: $durationMs ms | Mode: $overlapMode | EQ: $eqMode | FX: $effectMode")

        val outgoingPlayer = activeDeck
        val incomingPlayer = standbyDeck

        // Ensure EQs are ready
        val currentEqOut = ensureEq(outgoingPlayer)
        val currentEqIn = ensureEq(incomingPlayer)

        // Get bands info for the mapping logic
        val bandsOut = currentEqOut?.numberOfBands ?: 0.toShort()
        val bandsIn = currentEqIn?.numberOfBands ?: 0.toShort()
        val minLevel = (-1500).toShort()

        incomingPlayer.play()

        fadeJob = mixerScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + durationMs

            while (isActive && System.currentTimeMillis() < endTime) {
                val elapsed = System.currentTimeMillis() - startTime
                // Normalize progress 0.0 -> 1.0
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)

                // 1. Get Calculated State from Mixer (Shared logic with Editor)
                val stateOut = TransitionMixer.getMixState("A", progress, overlapMode, eqMode, effectMode)
                val stateIn = TransitionMixer.getMixState("B", progress, overlapMode, eqMode, effectMode)

                // 2. Apply Volume
                outgoingPlayer.volume = stateOut.volume
                incomingPlayer.volume = stateIn.volume

                // 3. Apply Effects to Equalizers
                applyDeckStateToEQ(currentEqOut, stateOut, minLevel, bandsOut, effectMode)
                applyDeckStateToEQ(currentEqIn, stateIn, minLevel, bandsIn, effectMode)

                delay(30) // ~30 FPS updates
            }

            // Ensure final state
            outgoingPlayer.volume = 0f
            incomingPlayer.volume = 1f
            resetEQ(currentEqOut)
            resetEQ(currentEqIn)

            completeTransition()
        }
    }

    private fun completeTransition() {
        Log.i(TAG, "Transition Complete")

        // Swap decks
        val temp = activeDeck
        activeDeck = standbyDeck
        standbyDeck = temp

        // Swap EQ references so we know who is who next time
        val tempEq = eqA
        eqA = eqB
        eqB = tempEq

        // Swap Speed States
        val prevStandbySpeed = _standbyDeckSpeed.value
        _activeDeckSpeed.value = prevStandbySpeed
        _standbyDeckSpeed.value = 1f

        // Notify listener
        onActiveDeckChanged(activeDeck)

        // Slowly reset pitch to 1.0
        scheduleSpeedReset()

        // Clean up old deck
        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.volume = 1f
        standbyDeck.setPlaybackSpeed(1f)

        // Ensure active is clean
        activeDeck.volume = 1f
    }

    private fun scheduleSpeedReset(durationMs: Long = 10000) {
        speedJob?.cancel()

        val player = activeDeck
        val startSpeed = player.playbackParameters.speed

        if (startSpeed == 1f) {
            _activeDeckSpeed.value = 1f
            return
        }

        Log.i(TAG, "Ramping BPM from $startSpeed to 1.0 over ${durationMs}ms")

        speedJob = mixerScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + durationMs

            while (isActive && System.currentTimeMillis() < endTime) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = elapsed.toFloat() / durationMs

                // Linear interpolation
                val newSpeed = startSpeed + (1f - startSpeed) * progress
                player.setPlaybackSpeed(newSpeed)
                _activeDeckSpeed.value = newSpeed

                delay(100)
            }
            player.setPlaybackSpeed(1f)
            _activeDeckSpeed.value = 1f
        }
    }

    fun cancelCrossfade() {
        fadeJob?.cancel()
        activeDeck.volume = 1f
        standbyDeck.volume = 0f
        standbyDeck.pause()
        resetEQ(eqA)
        resetEQ(eqB)
    }

    // --- Equalizer Helpers ---

    private fun ensureEq(player: ExoPlayer): Equalizer? {
        // If player session ID is invalid, we can't attach
        if (player.audioSessionId == 0) return null

        val isPlayerA = (player == playerA)
        var eq = if (isPlayerA) eqA else eqB

        // Create if missing or if session ID changed
        if (eq == null) {
            try {
                eq = Equalizer(0, player.audioSessionId)
                eq.enabled = true
                if (isPlayerA) eqA = eq else eqB = eq
                Log.d(TAG, "Initialized EQ for Deck ${if (isPlayerA) "A" else "B"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init EQ", e)
            }
        }
        return eq
    }

    private fun resetEQ(eq: Equalizer?) {
        if (eq == null) return
        try {
            for (i in 0 until eq.numberOfBands) {
                eq.setBandLevel(i.toShort(), 0)
            }
        } catch (e: Exception) { Log.e(TAG, "Error resetting EQ", e) }
    }

    /**
     * Maps the abstract DeckState (Bass 0..1, Filter 0..1) to physical Android Equalizer bands.
     * This approximates a DJ mixer's Low/High pass filters.
     */
    private fun applyDeckStateToEQ(
        eq: Equalizer?,
        state: DeckState,
        minEQ: Short,
        bands: Short,
        effectMode: String
    ) {
        if (eq == null || bands < 1) return
        try {
            // 1. Bass Cut logic
            // We usually cut the lowest band (Index 0)
            val bassLevel = state.bass
            val bassCut = (minEQ * (1f - bassLevel)).toInt().toShort()

            eq.setBandLevel(0.toShort(), bassCut)
            // If we have enough bands, slightly cut the second one too for smoother roll-off
            if (bands > 1) {
                eq.setBandLevel(1.toShort(), (bassCut * 0.8).toInt().toShort())
            }

            // 2. Filter Logic (LPF / HPF)
            // Standard Android EQ is usually 5 bands.
            // Band 0: ~60Hz, Band 4: ~14kHz

            val isLPF = effectMode.contains("Low pass") // "Low pass in" or "Low Pass out"
            val isHPF = effectMode.contains("High Pass")
            val filterLevel = state.filterHigh

            if (isLPF) {
                // Low Pass: Attenuate High Frequencies
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()
                val lastBand = (bands - 1).toShort()

                eq.setBandLevel(lastBand, cut)
                if (bands > 2) {
                    val prevBand = (bands - 2).toShort()
                    eq.setBandLevel(prevBand, (cut * 0.7).toInt().toShort())
                }
            } else if (isHPF) {
                // High Pass: Attenuate Low Frequencies
                // Note: If Bass cut is also active, use whichever is stronger (minimum)
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()

                val currentBand0 = eq.getBandLevel(0.toShort())
                if (cut < currentBand0) eq.setBandLevel(0.toShort(), cut)

                if (bands > 1) {
                    val currentBand1 = eq.getBandLevel(1.toShort())
                    if (cut < currentBand1) eq.setBandLevel(1.toShort(), cut)
                }
                if (bands > 2) {
                    // Cut mids partially
                    eq.setBandLevel(2.toShort(), (cut * 0.5).toInt().toShort())
                }
            }
        } catch (e: Exception) {
            // EQ failures are non-critical, just log
            // Log.w(TAG, "EQ Set failed", e)
        }
    }

    fun release() {
        mixerScope.cancel()
        eqA?.release()
        eqB?.release()
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