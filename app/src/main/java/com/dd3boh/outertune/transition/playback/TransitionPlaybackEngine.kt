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
 *
 * Responsibilities:
 * - Managing ExoPlayer instances (A/B decks)
 * - Executing the PLL (Phase Locked Loop) synchronization
 * - Applying real-time Volume and EQ automation
 *
 * Constraints:
 * - Must NOT depend on UI state or Waveforms.
 * - Must use TransitionMath for all time conversions.
 */
class TransitionPlaybackEngine(
    private val context: Context
) {
    // --- Public State ---
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    data class PlaybackState(
        val isPlaying: Boolean = false,
        val currentBeatA: Float = 0f,
        val currentBeatB: Float = 0f,
        val phaseError: Float = 0f
    )

    // --- Internals ---
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var eqA: Equalizer? = null
    private var eqB: Equalizer? = null

    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Live configuration (Volatile to ensure visibility across threads if needed)
    @Volatile
    private var activeConfig: TransitionConfig? = null

    // Configuration constants
    private val PREROLL_SECONDS = 3.0
    private val PHASE_ERROR_THRESHOLD = 0.5
    private val TAG = "TransitionPlayback"

    /**
     * Initializes the players. Call this once when the screen opens.
     */
    fun prepare(uriA: String, uriB: String) {
        if (playerA == null) playerA = createPlayer()
        if (playerB == null) playerB = createPlayer()

        val mediaItemA = MediaItem.fromUri(Uri.fromFile(File(uriA)))
        val mediaItemB = MediaItem.fromUri(Uri.fromFile(File(uriB)))

        playerA?.setMediaItem(mediaItemA)
        playerB?.setMediaItem(mediaItemB)

        playerA?.prepare()
        playerB?.prepare()

        // Initialize EQs early if possible
        initEQs()
    }

    /**
     * Updates the mix configuration (Overlap, EQ mode) in real-time.
     */
    fun updateConfig(config: TransitionConfig) {
        this.activeConfig = config
    }

    /**
     * Starts the transition preview based on the provided Plan.
     */
    fun play(plan: TransitionPlan, config: TransitionConfig) {
        val pA = playerA ?: return
        val pB = playerB ?: return

        activeConfig = config // Set initial config
        initEQs() // Ensure EQs exist

        stop() // Stop existing playback
        _playbackState.value = _playbackState.value.copy(isPlaying = true)

        // --- 1. Calculate Start Times ---
        val timeAnchorA = TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA)
        val timeAnchorB = TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB)

        val seekA = (timeAnchorA - PREROLL_SECONDS).coerceAtLeast(0.0)

        // Track B preroll duration might need scaling if B is playing faster/slower
        val prerollB = if (plan.gridScalarB == 1.0) PREROLL_SECONDS else PREROLL_SECONDS * plan.initialSpeedB
        val seekB = (timeAnchorB - prerollB).coerceAtLeast(0.0)

        // --- 2. Setup Players ---
        pA.volume = 0f
        pB.volume = 0f // Start muted
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

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null

        playerA?.pause()
        playerB?.pause()
        playerA?.volume = 1f
        playerB?.volume = 1f
        playerA?.setPlaybackSpeed(1f)
        playerB?.setPlaybackSpeed(1f)

        resetEQ(eqA)
        resetEQ(eqB)

        _playbackState.value = PlaybackState(isPlaying = false)
    }

    fun release() {
        stop()
        playerA?.release()
        playerB?.release()
        eqA?.release()
        eqB?.release()
        playerA = null
        playerB = null
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

        // PLL State
        var isClockAActive = false
        var isClockBActive = false
        var lastPosA = -1.0
        var lastPosB = -1.0
        var clockBStartTime = 0L
        var currentAppliedSpeed = plan.initialSpeedB.toFloat()
        var hasSeekOccurred = false

        val loopStartTime = System.currentTimeMillis()

        // FIX: Use currentCoroutineContext().isActive instead of just isActive
        while (currentCoroutineContext().isActive) {
            // Check playing flag explicitly
            if (!_playbackState.value.isPlaying) break

            val currentConfig = activeConfig ?: return // Safety check

            val posA = pA.currentPosition / 1000.0
            val posB = pB.currentPosition / 1000.0

            // 1. Watchdog
            if (pA.playbackState == Player.STATE_READY && !pA.isPlaying) pA.play()
            if (pB.playbackState == Player.STATE_READY && !pB.isPlaying) pB.play()

            // 2. Clock Activation
            if (!isClockAActive) {
                if (posA > lastPosA + 0.001) isClockAActive = true
                lastPosA = posA
            }
            if (!isClockBActive) {
                if (posB > lastPosB + 0.001) {
                    isClockBActive = true
                    clockBStartTime = System.currentTimeMillis()
                }
                lastPosB = posB
            }

            if (!isClockAActive || !isClockBActive) {
                // Timeout check
                if (System.currentTimeMillis() - loopStartTime > 2000) {
                    Log.w(TAG, "Timeout waiting for players to start.")
                    break
                }
                delay(16)
                continue
            }

            // 3. Current Position in Beats
            val currentBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, posA)
            val elapsedBeatsA = currentBeatA - transitionStartBeatA
            val targetBeatB = plan.anchorBeatB + elapsedBeatsA
            val currentBeatB = TransitionMath.getBeatForTimestamp(plan.gridB, posB)

            // 4. Phase Error
            var phaseErrorBeats = targetBeatB - currentBeatB
            while (phaseErrorBeats > 0.5) phaseErrorBeats -= 1.0
            while (phaseErrorBeats < -0.5) phaseErrorBeats += 1.0

            _playbackState.value = PlaybackState(true, currentBeatA.toFloat(), currentBeatB.toFloat(), phaseErrorBeats.toFloat())

            // 5. Muted Preroll Seek Correction
            val isInPreroll = currentBeatA < transitionStartBeatA
            if (isInPreroll && !hasSeekOccurred) {
                if (abs(phaseErrorBeats) > PHASE_ERROR_THRESHOLD) {
                    val correctedTimeB = TransitionMath.getTimestampForBeat(plan.gridB, targetBeatB)
                    pB.seekTo((correctedTimeB * 1000).toLong())
                    hasSeekOccurred = true
                    currentAppliedSpeed = plan.initialSpeedB.toFloat()
                    pB.setPlaybackSpeed(currentAppliedSpeed)
                    delay(50)
                    continue
                }
            }

            // 6. Mixer Logic
            val progress = if (plan.transitionDurationBeats > 0)
                ((currentBeatA - transitionStartBeatA) / plan.transitionDurationBeats).toFloat()
            else 0f

            var volA = 0f
            var volB = 0f

            val eqBandsA = eqA?.numberOfBands ?: 0
            val eqBandsB = eqB?.numberOfBands ?: 0
            val minEQ = eqA?.bandLevelRange?.get(0) ?: -1500

            if (progress < 0f) {
                volA = if (currentBeatA >= unmuteBeatA) 1f else 0f
                volB = 0f
                resetEQ(eqA)
                resetEQ(eqB)
            } else if (progress <= 1f) {
                val stateA = TransitionMixer.getMixState("A", progress, currentConfig.overlapMode, currentConfig.eqMode, currentConfig.effectMode)
                val stateB = TransitionMixer.getMixState("B", progress, currentConfig.overlapMode, currentConfig.eqMode, currentConfig.effectMode)
                volA = stateA.volume
                volB = stateB.volume
                applyDeckStateToEQ(eqA, stateA, minEQ, eqBandsA, currentConfig.effectMode)
                applyDeckStateToEQ(eqB, stateB, minEQ, eqBandsB, currentConfig.effectMode)
            } else {
                volA = 0f
                volB = 1f
                resetEQ(eqB)
            }

            pA.volume = volA
            pB.volume = volB

            // 7. PLL Control Law
            val mixConfidence = volB.coerceIn(0f, 1f)
            val baseKp = 0.03f + (0.60f - 0.03f) * (1f - mixConfidence)

            val timeSinceClockB = System.currentTimeMillis() - clockBStartTime
            val warmupFactor = if (timeSinceClockB < 250) 1.4f else 1f
            val clockGain = (timeSinceClockB / 150f).coerceIn(0f, 1f)

            val effectiveKp = baseKp * warmupFactor * clockGain
            val shapedError = phaseErrorBeats.toFloat() / (1f + abs(phaseErrorBeats.toFloat()) * 4f)
            val correction = (shapedError * effectiveKp).coerceIn(-0.1f, 0.1f)
            val targetSpeed = (plan.initialSpeedB.toFloat() + correction).coerceIn(0.5f, 2.0f)

            if (abs(targetSpeed - currentAppliedSpeed) > 0.001f) {
                pB.setPlaybackSpeed(targetSpeed)
                currentAppliedSpeed = targetSpeed
            }

            delay(33)
        }
    }

    // --- ExoPlayer Factory ---
    private fun createPlayer(): ExoPlayer {
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
                            emptyArray(),
                            SilenceSkippingAudioProcessor(),
                            SonicAudioProcessor()
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

    // --- EQ Helpers ---
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
            // Bass logic
            val bassCut = (minEQ * (1f - state.bass)).toInt().toShort()
            eq.setBandLevel(0.toShort(), bassCut)
            if (bands > 1) eq.setBandLevel(1.toShort(), (bassCut * 0.8).toInt().toShort())

            // Filter logic
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