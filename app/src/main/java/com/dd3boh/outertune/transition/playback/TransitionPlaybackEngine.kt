package com.dd3boh.outertune.transition.playback

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.utils.DeckState
import com.dd3boh.outertune.utils.TransitionMixer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs

/**
 * Real-time audio engine for executing DJ transitions.
 * Implements "Hot-Deck" logic to minimize startup jitter.
 *
 * Uses SilenceAudioProcessor to keep decks hot without audible bleed.
 */
class TransitionPlaybackEngine(
    private val context: Context
) {
    // --- Public State ---
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Expose readiness to UI to prevent race conditions on Play button
    private val _decksReady = MutableStateFlow(false)
    val decksReady: StateFlow<Boolean> = _decksReady.asStateFlow()

    private val _loadingError = MutableStateFlow<String?>(null)
    val loadingError: StateFlow<String?> = _loadingError.asStateFlow()

    data class PlaybackState(
        val isPlaying: Boolean = false,
        val currentBeatA: Float = 0f,
        val currentBeatB: Float = 0f,
        val phaseError: Float = 0f
    )

    private enum class DeckWarmState {
        COLD,
        PREPARING,
        WARM,   // Buffered, AudioTrack allocated, Soft-Idle (Playing silently)
        ACTIVE  // Currently active in a transition
    }

    // --- Internals ---
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var eqA: Equalizer? = null
    private var eqB: Equalizer? = null

    // Silence Processors for each deck
    private val silenceProcessorA = SilenceAudioProcessor()
    private val silenceProcessorB = SilenceAudioProcessor()

    // Track internal lifecycle
    private var deckAState = DeckWarmState.COLD
    private var deckBState = DeckWarmState.COLD

    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var activeConfig: TransitionConfig? = null

    private val PREROLL_SECONDS = 3.0
    private val TAG = "TransitionPlayback"

    /**
     * Pre-warms both decks for instant playback.
     * This performs a "Silent Activation":
     * 1. Enables SilenceAudioProcessor (absolute silence).
     * 2. Prepares the ExoPlayer.
     * 3. Play at normal volume (but silenced by processor) to force AudioTrack allocation.
     * 4. Enters "soft-idle" mode.
     */
    fun prewarmDecks(uriA: String, uriB: String) {
        cancelControlLoopOnly()

        deckAState = DeckWarmState.PREPARING
        deckBState = DeckWarmState.PREPARING
        _decksReady.value = false
        _loadingError.value = null

        // Enable Silence for pre-warm
        silenceProcessorA.isEnabled = true
        silenceProcessorB.isEnabled = true

        if (playerA == null) playerA = createPlayer("A")
        if (playerB == null) playerB = createPlayer("B")

        val mediaItemA = MediaItem.fromUri(Uri.fromFile(File(uriA)))
        val mediaItemB = MediaItem.fromUri(Uri.fromFile(File(uriB)))

        // Reset players
        playerA?.apply {
            setMediaItem(mediaItemA)
            volume = 1.0f // Full volume, handled by SilenceProcessor
            repeatMode = Player.REPEAT_MODE_OFF
            setPlaybackSpeed(1.0f)
        }

        playerB?.apply {
            setMediaItem(mediaItemB)
            volume = 1.0f // Full volume, handled by SilenceProcessor
            repeatMode = Player.REPEAT_MODE_OFF
            setPlaybackSpeed(1.0f)
        }

        eqA?.release(); eqA = null
        eqB?.release(); eqB = null

        // Launch prewarm sequence
        scope.launch {
            val jobA = async { warmUpDeck(playerA, "A") }
            val jobB = async { warmUpDeck(playerB, "B") }

            val resultA = jobA.await()
            val resultB = jobB.await()

            if (resultA) deckAState = DeckWarmState.WARM
            if (resultB) deckBState = DeckWarmState.WARM

            // Initialize EQs now that AudioSessions are generated
            initEQs()

            Log.d(TAG, "Decks prewarmed: A=$deckAState, B=$deckBState")

            if (resultA && resultB) {
                _decksReady.value = true
            } else {
                 if (!resultA) _loadingError.value = "Deck A Failed to Warm Up"
                 if (!resultB) _loadingError.value = "Deck B Failed to Warm Up"
            }
        }
    }

    /**
     * Internal logic to force ExoPlayer to "bite" the audio stream.
     */
    private suspend fun warmUpDeck(player: ExoPlayer?, label: String): Boolean {
        if (player == null) return false
        return try {
            player.prepare()

            withTimeoutOrNull(1500) {
                while (player.playbackState != Player.STATE_READY) {
                    delay(10)
                }
            } ?: run {
                Log.e(TAG, "Deck $label timed out waiting for STATE_READY")
                _loadingError.value = "Deck $label timeout (Ready)"
                return false
            }

            // "Bite" the stream
            player.play()
            val startPos = player.currentPosition

            withTimeoutOrNull(1500) {
                while (player.currentPosition <= startPos) {
                    delay(10)
                }
            } ?: run {
                Log.e(TAG, "Deck $label timed out waiting for playback start")
                _loadingError.value = "Deck $label timeout (Playback)"
                return false
            }

            // Soft-idle. Do NOT pause. Keep playing silently (via processor).
            player.setPlaybackSpeed(1f)
            player.seekTo(500L)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to warm up deck $label", e)
            _loadingError.value = "Deck $label error: ${e.message}"
            false
        }
    }

    fun updateConfig(config: TransitionConfig) {
        this.activeConfig = config
    }

    /**
     * Starts the transition preview.
     * Assumes decks are already WARM (Soft-Idle).
     */
    fun play(plan: TransitionPlan, config: TransitionConfig) {
        val pA = playerA ?: return
        val pB = playerB ?: return

        if (deckAState != DeckWarmState.WARM || deckBState != DeckWarmState.WARM) {
            Log.e(TAG, "Play called on cold decks - Ignoring request to prevent jitter.")
            return
        }

        activeConfig = config
        initEQs()

        cancelControlLoopOnly()
        _playbackState.value = _playbackState.value.copy(isPlaying = true)

        // --- 1. Calculate Start Times ---
        val timeAnchorA = TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA)
        val timeAnchorB = TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB)

        val seekA = (timeAnchorA - PREROLL_SECONDS).coerceAtLeast(0.0)
        val prerollB = if (plan.gridScalarB == 1.0) PREROLL_SECONDS else PREROLL_SECONDS * plan.initialSpeedB
        val seekB = (timeAnchorB - prerollB).coerceAtLeast(0.0)

        // --- 2. Setup Players ---
        // Disable silence for Deck A (it needs to be heard immediately usually, or controlled by mixer)
        // Deck B starts silent until mixed in
        // Ideally, we keep silence ENABLED until the loop decides to unmute them.
        // But for instant start, let's keep them silenced and let the loop unmute.
        
        // Actually, for safety, let's rely on volume 0 initially in the loop, 
        // OR better: flip silence OFF but set volume to 0.001 (or 0) via mixer.
        // BUT the whole point was to avoid volume 0.
        // So:
        silenceProcessorA.isEnabled = false
        silenceProcessorB.isEnabled = false
        
        // Set initial volumes
        pA.volume = 0f 
        pB.volume = 0f

        pA.setPlaybackSpeed(1.0f)
        pB.setPlaybackSpeed(plan.initialSpeedB.toFloat())

        pA.seekTo((seekA * 1000).toLong())
        pB.seekTo((seekB * 1000).toLong())

        pA.play()
        pB.play()

        // --- 3. Start Control Loop ---
        playbackJob = scope.launch {
            runControlLoop(pA, pB, plan, seekA)
        }
    }

    /**
     * Stops playback and returns to Soft-Idle state.
     */
    fun stop() {
        cancelControlLoopOnly()

        // Re-enable Silence
        silenceProcessorA.isEnabled = true
        silenceProcessorB.isEnabled = true

        // Keep clock hot
        playerA?.apply {
            volume = 1f
            setPlaybackSpeed(1f)
            seekTo(currentPosition) // Flush buffer to kill residual sound
            play()
        }
        playerB?.apply {
            volume = 1f
            setPlaybackSpeed(1f)
            seekTo(currentPosition) // Flush buffer to kill residual sound
            play()
        }

        resetEQ(eqA)
        resetEQ(eqB)

        deckAState = DeckWarmState.WARM
        deckBState = DeckWarmState.WARM

        _playbackState.value = PlaybackState(isPlaying = false)
    }

    private fun cancelControlLoopOnly() {
        playbackJob?.cancel()
        playbackJob = null
    }

    fun release() {
        cancelControlLoopOnly()
        playerA?.release()
        playerB?.release()
        eqA?.release()
        eqB?.release()
        playerA = null
        playerB = null
        eqA = null
        eqB = null
        deckAState = DeckWarmState.COLD
        deckBState = DeckWarmState.COLD
        _decksReady.value = false
    }

    // --- The Core Loop (PLL & Mixer) ---

    private suspend fun runControlLoop(
        pA: ExoPlayer,
        pB: ExoPlayer,
        plan: TransitionPlan,
        seekTimeA: Double
    ) {
        val transitionStartBeatA = plan.anchorBeatA
        val unmuteBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, seekTimeA + 0.5)

        var currentAppliedSpeed = plan.initialSpeedB.toFloat()
        var isAligned = false
        var firstTick = true

        delay(33) // Allow seek to register

        while (currentCoroutineContext().isActive) {
            if (!_playbackState.value.isPlaying) break

            val currentConfig = activeConfig ?: break

            if (firstTick) {
                deckAState = DeckWarmState.ACTIVE
                deckBState = DeckWarmState.ACTIVE
                firstTick = false
            }

            val posA = pA.currentPosition / 1000.0
            val posB = pB.currentPosition / 1000.0

            // 1. Watchdog
            if (pA.playbackState == Player.STATE_READY && !pA.isPlaying) pA.play()
            if (pB.playbackState == Player.STATE_READY && !pB.isPlaying) pB.play()

            // 2. Current Position in Beats
            val currentBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, posA)
            val elapsedBeatsA = currentBeatA - transitionStartBeatA
            val targetBeatB = plan.anchorBeatB + elapsedBeatsA
            val currentBeatB = TransitionMath.getBeatForTimestamp(plan.gridB, posB)

            // 3. Phase Error Calculation
            var phaseErrorBeats = targetBeatB - currentBeatB
            val isInPreroll = currentBeatA < transitionStartBeatA

            // 4. Preroll Correction
            if (isInPreroll) {
                if (!isAligned && abs(phaseErrorBeats) > 0.2) {
                    val correctedTimeB = TransitionMath.getTimestampForBeat(plan.gridB, targetBeatB)
                    pB.seekTo((correctedTimeB * 1000).toLong())

                    currentAppliedSpeed = plan.initialSpeedB.toFloat()
                    pB.setPlaybackSpeed(currentAppliedSpeed)
                    delay(50)
                    continue
                } else if (!isAligned && abs(phaseErrorBeats) < 0.05) {
                    isAligned = true
                }
            } else {
                while (phaseErrorBeats > 0.5) phaseErrorBeats -= 1.0
                while (phaseErrorBeats < -0.5) phaseErrorBeats += 1.0
            }

            _playbackState.value = PlaybackState(
                true,
                currentBeatA.toFloat(),
                currentBeatB.toFloat(),
                phaseErrorBeats.toFloat()
            )

            // 5. Mixer Logic
            val progress = if (plan.transitionDurationBeats > 0)
                ((currentBeatA - transitionStartBeatA) / plan.transitionDurationBeats).toFloat()
            else 0f

            var volA: Float
            var volB: Float

            val minEQ = eqA?.bandLevelRange?.get(0) ?: -1500

            if (progress < 0f) {
                volA = if (currentBeatA >= unmuteBeatA) 1f else 0f
                volB = 0f
                if (currentBeatA >= unmuteBeatA) {
                    resetEQ(eqA)
                    resetEQ(eqB)
                }
            } else if (progress <= 1f) {
                val stateA = TransitionMixer.getMixState("A", progress, currentConfig.overlapMode, currentConfig.eqMode, currentConfig.effectMode)
                val stateB = TransitionMixer.getMixState("B", progress, currentConfig.overlapMode, currentConfig.eqMode, currentConfig.effectMode)
                volA = stateA.volume
                volB = stateB.volume
                applyDeckStateToEQ(eqA, stateA, minEQ, (eqA?.numberOfBands ?: 0).toShort(), currentConfig.effectMode)
                applyDeckStateToEQ(eqB, stateB, minEQ, (eqB?.numberOfBands ?: 0).toShort(), currentConfig.effectMode)
            } else {
                volA = 0f
                volB = 1f
                resetEQ(eqB)
            }

            // Apply mixing volumes
            // Note: We used to coerceAtLeast(0.001f). Now we can go to true 0 because 
            // the pipeline is alive, BUT only if we trust Android not to kill a 0-volume track.
            // With SilenceProcessor disabled, we are relying on normal playback.
            // When playing active audio, we DON'T want 0.001 bleed if it should be silent.
            // But we do want to avoid suspension.
            // Since decks are ACTIVE, let's stick to true volume logic.
            // If the user wants to be super safe, we can keep 0.001 but that defeats the purpose of "Silence" processor? 
            // No, Silence processor is for the IDLE state.
            // In ACTIVE state, if volume is 0, we genuinely want 0.
            pA.volume = volA
            pB.volume = volB

            // 6. PLL Control Law
            val mixConfidence = volB.coerceIn(0f, 1f)
            val kp = 0.04f + (0.50f * (1f - mixConfidence))

            val correction = if (abs(phaseErrorBeats) > 0.005) {
                (phaseErrorBeats.toFloat() * kp).coerceIn(-0.1f, 0.1f)
            } else 0f

            val targetSpeed = (plan.initialSpeedB.toFloat() + correction).coerceIn(0.5f, 2.0f)

            if (abs(targetSpeed - currentAppliedSpeed) > 0.002f) {
                pB.setPlaybackSpeed(targetSpeed)
                currentAppliedSpeed = targetSpeed
            }

            delay(33)
        }
    }

    // --- ExoPlayer Factory ---
    private fun createPlayer(label: String): ExoPlayer {
        val silenceProcessor = if (label == "A") silenceProcessorA else silenceProcessorB

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                pcmEncodingRestrictionLifted: Boolean,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            SilenceSkippingAudioProcessor(),
                            SonicAudioProcessor(),
                            silenceProcessor // Always in the chain, controlled by .isEnabled
                        )
                    )
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun initEQs() {
        val pA = playerA ?: return
        val pB = playerB ?: return

        if (eqA == null && pA.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            try { eqA = Equalizer(0, pA.audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.e(TAG, "EQ A init failed", e) }
        }
        if (eqB == null && pB.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            try { eqB = Equalizer(0, pB.audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.e(TAG, "EQ B init failed", e) }
        }
    }

    private fun resetEQ(eq: Equalizer?) {
        if (eq == null) return
        try {
            for (i in 0 until eq.numberOfBands) eq.setBandLevel(i.toShort(), 0)
        } catch (e: Exception) {}
    }

    private fun applyDeckStateToEQ(
        eq: Equalizer?,
        state: DeckState,
        minEQ: Short,
        bands: Short,
        effectMode: String
    ) {
        if (eq == null || bands < 1) return
        try {
            val bassCut = (minEQ * (1f - state.bass)).toInt().toShort()
            eq.setBandLevel(0.toShort(), bassCut)
            if (bands > 1) eq.setBandLevel(1.toShort(), (bassCut * 0.8).toInt().toShort())

            val isLPF = effectMode.contains("Low pass", true)
            val isHPF = effectMode.contains("High Pass", true)
            val filterLevel = state.filterHigh

            if (isLPF) {
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()
                val lastBand = (bands - 1).toShort()
                eq.setBandLevel(lastBand, cut)
                if (bands > 2) eq.setBandLevel((bands - 2).toShort(), (cut * 0.7).toInt().toShort())
            } else if (isHPF) {
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()
                eq.setBandLevel(0.toShort(), cut)
                if (bands > 1) eq.setBandLevel(1.toShort(), cut)
                if (bands > 2) eq.setBandLevel(2.toShort(), (cut * 0.5).toInt().toShort())
            }
        } catch (e: Exception) {
            Log.e(TAG, "EQ apply failed", e)
        }
    }
}