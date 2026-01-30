package com.dd3boh.outertune.playback

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.utils.DeckState
import com.dd3boh.outertune.utils.TransitionMixer
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.dd3boh.outertune.transition.playback.SilenceAudioProcessor
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Manages two ExoPlayer instances (Deck A and Deck B) to enable seamless mixing.
 * Handles Volume Crossfading, Speed Ramping (Beatmatching), and EQ/Filter Effects.
 */
class DeckManager(
    private val context: Context,
    private val dataSourceFactoryProvider: () -> DataSource.Factory,
    private val onActiveDeckChanged: (ExoPlayer) -> Unit
) {
    private val TAG = "DeckManager"

    // Silence Processors for Hot-Decking
    private val silenceProcessorA = SilenceAudioProcessor()
    private val silenceProcessorB = SilenceAudioProcessor()

    val playerA: ExoPlayer = createPlayer("A")
    // Deck B only created when needed, saving resources
    private val playerB: ExoPlayer by lazy {
        Log.d(TAG, "Creating Deck B for DJ mode")
        createPlayer("B")
    }

    // Audio Effects (Graphic Equalizer)
    private var eqA: Equalizer? = null
    private var eqB: Equalizer? = null

    var activeDeck: ExoPlayer = playerA
        private set
    var standbyDeck: ExoPlayer = getStandby()
        private set

    private fun getStandby(): ExoPlayer {
        // If active is A, standby is B (init if needed)
        // This circular dependency lazy logic is tricky.
        // Let's just rely on logic:
        return if (activeDeck == playerA) playerB else playerA
    }
    
    // ...

    private val _activeDeckSpeed = MutableStateFlow(1f)
    val activeDeckSpeed = _activeDeckSpeed.asStateFlow()

    private val _standbyDeckSpeed = MutableStateFlow(1f)
    val standbyDeckSpeed = _standbyDeckSpeed.asStateFlow()
    // ------------------------------------

    private val mixerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fadeJob: Job? = null
    private var speedJob: Job? = null

    private val _isCrossfading = MutableStateFlow(false)
    val isCrossfading = _isCrossfading.asStateFlow()

    private var activeMultiplier = 1f
    private var standbyMultiplier = 1f

    fun setStandbyVolumeMultiplier(multiplier: Float) {
        standbyMultiplier = multiplier
        Log.d(TAG, "Standby Multiplier set to $multiplier")
    }

    private fun completeTransition() {
        Log.e(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.e(TAG, "║          completeTransition() CALLED                       ║")
        Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")

        Log.e(TAG, "BEFORE SWAP:")
        Log.e(TAG, "  Active: ${if (activeDeck == playerA) "A" else "B"} - playing=${activeDeck.isPlaying}, pos=${activeDeck.currentPosition}")
        Log.e(TAG, "  Standby: ${if (standbyDeck == playerA) "A" else "B"} - playing=${standbyDeck.isPlaying}, pos=${standbyDeck.currentPosition}")

        // Swap decks
        val temp = activeDeck
        activeDeck = standbyDeck
        standbyDeck = temp

        Log.e(TAG, "AFTER SWAP:")
        Log.e(TAG, "  Active: ${if (activeDeck == playerA) "A" else "B"} - playing=${activeDeck.isPlaying}, pos=${activeDeck.currentPosition}")
        Log.e(TAG, "  Standby: ${if (standbyDeck == playerA) "A" else "B"} - playing=${standbyDeck.isPlaying}, pos=${standbyDeck.currentPosition}")

        // Swap EQ references
        val tempEq = eqA
        eqA = eqB
        eqB = tempEq

        // Swap Speed States
        val prevStandbySpeed = _standbyDeckSpeed.value
        _activeDeckSpeed.value = prevStandbySpeed
        _standbyDeckSpeed.value = 1f

        Log.e(TAG, "  Speed swapped: active=${_activeDeckSpeed.value}, standby=${_standbyDeckSpeed.value}")

        // Swap Multipliers
        activeMultiplier = standbyMultiplier
        standbyMultiplier = 1f

        Log.e(TAG, "  Multipliers swapped: active=$activeMultiplier, standby=$standbyMultiplier")

        // Notify listener
        Log.e(TAG, "  → Calling onActiveDeckChanged callback...")
        onActiveDeckChanged(activeDeck)
        Log.e(TAG, "  → Callback returned")

        // Slowly reset pitch to 1.0
        scheduleSpeedReset()

        // Clean up old deck
        Log.e(TAG, "  → Stopping old deck (now standby)...")
        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.volume = 1f
        standbyDeck.setPlaybackSpeed(1f)
        Log.e(TAG, "  → Old deck cleaned up")

        // Ensure active maintains its normalized volume
        activeDeck.volume = activeMultiplier
        Log.e(TAG, "  → Set new active deck volume to $activeMultiplier")

        _isCrossfading.value = false
        Log.e(TAG, "  → isCrossfading = false")
        Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
    }


    fun startPllTransition(
        plan: TransitionPlan,
        durationMs: Long,
        config: TransitionConfig
    ) {
        Log.e(TAG, "████████████████████████████████████████████████████████████")
        Log.e(TAG, "startPllTransition() CALLED")
        Log.e(TAG, "  Transition Duration: ${durationMs}ms")
        Log.e(TAG, "  Exit Beat A: ${plan.anchorBeatA}")
        Log.e(TAG, "  Entry Beat B: ${plan.anchorBeatB}")
        Log.e(TAG, "  Initial Speed B: ${plan.initialSpeedB}")
        Log.e(TAG, "  Grid A Size: ${plan.gridA.size}")
        Log.e(TAG, "  Grid B Size: ${plan.gridB.size}")
        Log.e(TAG, "  Grid A [0]: ${if (plan.gridA.isNotEmpty()) plan.gridA[0] else "EMPTY"}")
        Log.e(TAG, "  Grid B [0]: ${if (plan.gridB.isNotEmpty()) plan.gridB[0] else "EMPTY"}")

        if (fadeJob?.isActive == true) {
            Log.e(TAG, "  ⚠️ FADE JOB ALREADY ACTIVE - ABORTING!")
            Log.e(TAG, "████████████████████████████████████████████████████████████")
            return
        }

        // STRICT VALIDATION
        if (plan.gridA.isEmpty() || plan.gridB.isEmpty()) {
            Log.e(TAG, "  ⚠️ STRICT: Grids are empty! Aborting Transition.")
            Log.e(TAG, "████████████████████████████████████████████████████████████")
            return
        }
        // If we are exiting a song (not at start), anchor beat A must be > 0.
        // We allow close to 0 if the user set a transition at 0.1s, but 0.0 usually means "empty grid" returned 0.
        if (plan.exitPointMs > 5000 && plan.anchorBeatA < 1.0) {
             Log.e(TAG, "  ⚠️ STRICT: Exit Point ${plan.exitPointMs}ms but Exit Beat ${plan.anchorBeatA}. Invalid Plan.")
             Log.e(TAG, "████████████████████████████████████████████████████████████")
             return
        }

        // 1. Setup
        val pA = activeDeck
        val pB = standbyDeck
        // Use current volume to prevent jumps
        val outMult = pA.volume
        val inMult = standbyMultiplier

        Log.e(TAG, "  Active Deck: ${if (pA == playerA) "A" else "B"}")
        Log.e(TAG, "  Standby Deck: ${if (pB == playerA) "A" else "B"}")
        Log.e(TAG, "  Active Deck State: playing=${pA.isPlaying}, state=${pA.playbackState}, pos=${pA.currentPosition}")
        Log.e(TAG, "  Standby Deck State: playing=${pB.isPlaying}, state=${pB.playbackState}, pos=${pB.currentPosition}")

        // Unmute Standby (Silence Processor handles silence until we want sound)
        if (pB == playerA) {
            silenceProcessorA.isEnabled = false
            Log.e(TAG, "  → Disabled Silence Processor A")
        } else {
            silenceProcessorB.isEnabled = false
            Log.e(TAG, "  → Disabled Silence Processor B")
        }

        // Ensure EQ
        val eqOut = ensureEq(pA)
        val eqIn = ensureEq(pB)
        Log.e(TAG, "  → EQ initialized: Out=${eqOut != null}, In=${eqIn != null}")

        // Calculate the actual start position (3 seconds before entry point)
        val prerollSeconds = 3.0
        val startB = (TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB) - (prerollSeconds * plan.initialSpeedB)).coerceAtLeast(0.0)

        Log.e(TAG, "  → Calculated Start B: ${startB}s (${startB * 1000}ms)")

        Log.e(TAG, "  → Calculated Start B: ${startB}s (${startB * 1000}ms)")

        // IMPORTANT: Seek to the correct position and set speed BEFORE starting playback
        // Optimization: If we are already close to the target position (pre-warmed), SKIP SEEK to avoid buffering!
        val currentPos = pB.currentPosition
        val targetPos = (startB * 1000).toLong()
        
        if (abs(currentPos - targetPos) > 100) {
            Log.e(TAG, "  → Seeking Standby from $currentPos to $targetPos...")
            pB.seekTo(targetPos)
        } else {
             Log.e(TAG, "  → Standby already at $currentPos (Target $targetPos). SKIPPING SEEK to avoid buffer.")
        }
        
        pB.setPlaybackSpeed(plan.initialSpeedB.toFloat())
        _standbyDeckSpeed.value = plan.initialSpeedB.toFloat()

        Log.e(TAG, "  → Set Standby speed to ${plan.initialSpeedB}")

        // NOW start playback - this is crucial!
        pB.playWhenReady = true
        pB.play()

        Log.e(TAG, "  → Called pB.play() - Standby should now be PLAYING")
        Log.e(TAG, "  → Standby State After Play: playing=${pB.isPlaying}, playWhenReady=${pB.playWhenReady}")

        _isCrossfading.value = true
        Log.e(TAG, "  → Set isCrossfading = true")

        fadeJob = mixerScope.launch {
            delay(50)
            Log.e(TAG, "  → PLL Loop Starting (after 50ms delay)")
            Log.e(TAG, "  → Final check - Standby playing=${pB.isPlaying}, pos=${pB.currentPosition}")
            Log.e(TAG, "████████████████████████████████████████████████████████████")
            runPllLoop(pA, pB, plan, durationMs, config, outMult, inMult, eqOut, eqIn)
        }
    }

    private suspend fun runPllLoop(
        pA: ExoPlayer,
        pB: ExoPlayer,
        plan: TransitionPlan,
        durationMs: Long,
        config: TransitionConfig,
        outMult: Float,
        inMult: Float,
        eqOut: Equalizer?,
        eqIn: Equalizer?
    ) {
        Log.i(TAG, "▶▶▶ runPllLoop() STARTED ◀◀◀")

        val transitionStartBeatA = plan.anchorBeatA
        var currentAppliedSpeed = plan.initialSpeedB.toFloat()

        val bandsOut = eqOut?.numberOfBands ?: 0.toShort()
        val bandsIn = eqIn?.numberOfBands ?: 0.toShort()
        val minLevel = (-1500).toShort()

        Log.d(TAG, "PLL Config: AnchorBeatA=$transitionStartBeatA, InitSpeed=$currentAppliedSpeed")

        var loopCount = 0
        val startTime = System.currentTimeMillis()

        while (currentCoroutineContext().isActive) {
            loopCount++
            val posA = pA.currentPosition / 1000.0
            val posB = pB.currentPosition / 1000.0

            // Log every 500ms
            if (loopCount % 25 == 1) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "PLL Loop #$loopCount (${elapsed}ms): " +
                        "PosA=${posA}s, PosB=${posB}s, " +
                        "PlayingA=${pA.isPlaying}, PlayingB=${pB.isPlaying}, " +
                        "StateA=${pA.playbackState}, StateB=${pB.playbackState}, " +
                        "VolA=${pA.volume}, VolB=${pB.volume}")
            }

            // --- 1. Beat Calculations ---
            val currentBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, posA)
            val elapsedBeatsA = currentBeatA - transitionStartBeatA

            // Phase Locking
            val targetBeatB = plan.anchorBeatB + elapsedBeatsA
            val currentBeatB = TransitionMath.getBeatForTimestamp(plan.gridB, posB)

            var phaseError = targetBeatB - currentBeatB

            if (loopCount % 50 == 1) { // Log beat details every ~1s (loop runs at 20ms)
                Log.d(TAG, "  [Beats] CurA=$currentBeatA, ElapA=$elapsedBeatsA, TgtB=$targetBeatB, CurB=$currentBeatB")
                Log.d(TAG, "  [Phase] Error=$phaseError")
            }

            // --- 2. Phase Correction (Nudge) ---
            if (abs(phaseError) > 0.005) {
                val kp = 0.1f
                val nudge = (phaseError * kp).coerceIn(-0.1, 0.1)
                val newSpeed = (plan.initialSpeedB + nudge).toFloat().coerceIn(0.5f, 2.0f)

                if (abs(newSpeed - currentAppliedSpeed) > 0.002f) {
                    Log.d(TAG, "  [Adjustment] PhaseError=$phaseError -> Nudge=$nudge -> NewSpeed=$newSpeed")
                    pB.setPlaybackSpeed(newSpeed)
                    currentAppliedSpeed = newSpeed
                    _standbyDeckSpeed.value = newSpeed

                    if (loopCount % 25 == 1) {
                        Log.d(TAG, "  Phase correction: error=$phaseError, newSpeed=$newSpeed")
                    }
                }
            }

            // --- 3. Mixing ---
            val progress = if (durationMs > 0)
                ((pA.currentPosition - (TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA)*1000)).toFloat() / durationMs).coerceIn(-1f, 2f)
            else 1f

            if (progress < 0f) {
                // PREROLL
                pA.volume = 1f * outMult
                pB.volume = 0f // SILENT

                if (loopCount % 25 == 1) {
                    Log.d(TAG, "  PREROLL: progress=$progress")
                }
            } else if (progress <= 1f) {
                // CROSSFADE ACTIVE
                val stateOut = TransitionMixer.getMixState("A", progress, config.overlapMode, config.eqMode, config.effectMode)
                val stateIn = TransitionMixer.getMixState("B", progress, config.overlapMode, config.eqMode, config.effectMode)

                pA.volume = stateOut.volume * outMult
                pB.volume = stateIn.volume * inMult

                if (loopCount % 25 == 1) {
                    Log.d(TAG, "  CROSSFADE: progress=$progress, volA=${pA.volume}, volB=${pB.volume}")
                }

                applyDeckStateToEQ(eqOut, stateOut, minLevel, bandsOut, config.effectMode)
                applyDeckStateToEQ(eqIn, stateIn, minLevel, bandsIn, config.effectMode)
            } else {
                // FINISHED
                Log.i(TAG, "▶▶▶ PLL Loop FINISHED (progress=$progress) ◀◀◀")
                break
            }

            delay(20)
        }

        Log.i(TAG, "▶▶▶ runPllLoop() EXITING - Calling completeTransition() ◀◀◀")
        completeTransition()
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

    // --- Player Creation & Hot Decking ---

    private fun createPlayer(label: String): ExoPlayer {
        val silenceProcessor = if (label == "A") silenceProcessorA else silenceProcessorB
        
        // We use the same factory logic as TransitionPlaybackEngine to ensure consistency
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                pcmEncodingRestrictionLifted: Boolean,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setEnableFloatOutput(enableFloatOutput) // Enable 32-bit Float High-Res Audio
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor(),
                            androidx.media3.common.audio.SonicAudioProcessor(),
                            silenceProcessor // INJECTED SILENCE PROCESSOR
                        )
                    )
                    .build()
            }
        }.apply {
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactoryProvider()))
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), false) // No Auto Focus
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (_isCrossfading.value && player == activeDeck) {
                    if (standbyDeck.playWhenReady != playWhenReady) {
                        Log.d(TAG, "Syncing standby deck to active deck state: playWhenReady=$playWhenReady")
                        standbyDeck.playWhenReady = playWhenReady
                    }
                }
            }
        })

        return player
    }

    fun prepareNext(mediaItem: MediaItem, startPositionMs: Long, bpmConfig: Float?) {
        Log.d(TAG, "════════════════════════════════════════════════════════════")
        Log.d(TAG, "prepareNext() CALLED")
        Log.d(TAG, "  Song: ${mediaItem.mediaMetadata.title}")
        Log.d(TAG, "  Start Position: ${startPositionMs}ms")
        Log.d(TAG, "  BPM Config: $bpmConfig")
        Log.d(TAG, "  Current Active Deck: ${if (activeDeck == playerA) "A" else "B"}")
        Log.d(TAG, "  Current Standby Deck: ${if (standbyDeck == playerA) "A" else "B"}")
        Log.d(TAG, "════════════════════════════════════════════════════════════")

        // 1. Reset
        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.setMediaItem(mediaItem)

        // 2. Enable Silence (Hot Deck)
        if (standbyDeck == playerA) {
            silenceProcessorA.isEnabled = true
            Log.d(TAG, "  → Enabled Silence Processor A")
        } else {
            silenceProcessorB.isEnabled = true
            Log.d(TAG, "  → Enabled Silence Processor B")
        }

        // 3. Set Volume to 0 (Silence processor also helps)
        standbyDeck.volume = 0f
        Log.d(TAG, "  → Set standby volume to 0")

        // 4. Seek & Speed
        standbyDeck.seekTo(startPositionMs)
        val speed = bpmConfig ?: 1f
        standbyDeck.setPlaybackSpeed(speed)
        _standbyDeckSpeed.value = speed
        Log.d(TAG, "  → Seek to ${startPositionMs}ms, speed: $speed")

        standbyDeck.prepare()
        Log.d(TAG, "  → Called prepare()")

        // 5. "Bite and HOLD" - Keep it prepared but paused
        mixerScope.launch {
            Log.d(TAG, "  → Waiting for standby deck to be READY...")
            var waitCount = 0
            while (standbyDeck.playbackState != Player.STATE_READY) {
                delay(10)
                waitCount++
                if (waitCount % 100 == 0) {
                    Log.d(TAG, "    Still waiting... State: ${standbyDeck.playbackState}")
                }
            }

            Log.d(TAG, "  → Standby deck is READY!")
            standbyDeck.seekTo(startPositionMs)
            standbyDeck.playWhenReady = false
            Log.d(TAG, "  → Set playWhenReady=false, deck is PAUSED and ready")
            Log.d(TAG, "════════════════════════════════════════════════════════════")
        }

        ensureEq(standbyDeck)
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