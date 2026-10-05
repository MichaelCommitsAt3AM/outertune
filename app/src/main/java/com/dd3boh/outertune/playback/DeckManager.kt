package com.dd3boh.outertune.playback

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.transition.playback.MixDiagnostics
import com.dd3boh.outertune.transition.playback.PhaseController
import com.dd3boh.outertune.utils.DeckState
import com.dd3boh.outertune.utils.TransitionMixer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.dd3boh.outertune.utils.DebugLog as Log

/**
 * Manages two ExoPlayer instances (Deck A and Deck B) for beatmatched transitions.
 *
 * The active deck is the one the user hears and the media session controls; the standby deck is
 * prepared with the next song and brought in by [startPllTransition], which phase-locks it to the
 * active deck, runs the crossfade/EQ automation and finally swaps the decks.
 *
 * All methods must be called on the main thread (the players' application looper).
 */
class DeckManager(
    private val context: Context,
    private val dataSourceFactoryProvider: () -> DataSource.Factory,
    /** [DefaultRenderersFactory.ExtensionRendererMode] from the user's decoder preference. */
    private val extensionRendererMode: Int,
    /** Called once for each deck right after it is built, to attach listeners. */
    private val onPlayerCreated: (ExoPlayer) -> Unit,
    private val onActiveDeckChanged: (ExoPlayer) -> Unit,
) {
    private val TAG = "DeckManager"

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Deck A handles audio focus first; focus handling moves with the active role on every swap,
    // so the standby deck never steals focus from the deck the user is hearing.
    val playerA: ExoPlayer = createPlayer(handleAudioFocus = true)
    private var playerBOrNull: ExoPlayer? = null
    private val playerB: ExoPlayer
        get() = playerBOrNull ?: createPlayer(handleAudioFocus = false).also {
            Log.d(TAG, "Creating Deck B for mix mode")
            playerBOrNull = it
        }

    var activeDeck: ExoPlayer = playerA
        private set

    /** The deck that is not active. Creates Deck B on first use. */
    val standbyDeck: ExoPlayer
        get() = if (activeDeck === playerA) playerB else playerA

    private val mixerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fadeJob: Job? = null
    private var speedJob: Job? = null
    private var prepareJob: Job? = null

    private val _isCrossfading = MutableStateFlow(false)
    val isCrossfading: StateFlow<Boolean> = _isCrossfading.asStateFlow()

    /** Progress through the transition zone: < 0 during preroll, 0..1 during the crossfade. */
    private val _crossfadeProgress = MutableStateFlow(0f)
    val crossfadeProgress: StateFlow<Float> = _crossfadeProgress.asStateFlow()

    /** Gain the active deck had when the crossfade started; restored if it's cancelled. */
    private var outGain = 1f
    /** Gain the incoming deck should end up at (loudness normalization × user volume). */
    private var inGain = 1f

    private val equalizers = HashMap<ExoPlayer, DeckEqualizer>()

    /**
     * Starts bringing in the standby deck. Must be called about [PREROLL_SECONDS] before the
     * plan's exit point while the active deck is playing.
     *
     * @param incomingGain final volume for the incoming deck
     * @return false if the plan was rejected and nothing was started
     */
    fun startPllTransition(plan: TransitionPlan, config: TransitionConfig, incomingGain: Float): Boolean {
        if (fadeJob?.isActive == true) {
            Log.w(TAG, "startPllTransition: a transition is already running")
            return false
        }
        if (plan.gridA.isEmpty() || plan.gridB.isEmpty() || plan.transitionDurationBeats <= 0.0) {
            Log.w(TAG, "startPllTransition: plan has no grids or no length; skipping")
            return false
        }

        prepareJob?.cancel()
        val pA = activeDeck
        val pB = standbyDeck
        outGain = pA.volume
        inGain = incomingGain

        val gridScalar = plan.gridScalarB.takeIf { it > 0.0 } ?: 1.0
        // The plan's ratio assumes A plays at 1x; A may still be ramping back from its own mix.
        val baseSpeed = plan.initialSpeedB * pA.playbackParameters.speed

        // Seek B to where it should be a moment from now, so the initial phase error is small.
        val predictedTimeA = (pA.currentPosition + SEEK_LEAD_MS) / 1000.0
        val elapsedBeatsA = TransitionMath.getBeatForTimestamp(plan.gridA, predictedTimeA) - plan.anchorBeatA
        val targetTimeB = TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB + elapsedBeatsA / gridScalar)

        pB.volume = 0f
        pB.seekTo((targetTimeB * 1000).toLong().coerceAtLeast(0))
        pB.setPlaybackSpeed(baseSpeed.toFloat())
        pB.playWhenReady = true
        pB.play()

        _crossfadeProgress.value = (elapsedBeatsA / plan.transitionDurationBeats).toFloat()
        _isCrossfading.value = true

        val diagnostics = MixDiagnostics(TAG, "playlist")
        fadeJob = mixerScope.launch {
            val outcome = runPllLoop(pA, pB, plan, config, gridScalar, baseSpeed, diagnostics)
            diagnostics.finish(outcome)
            completeTransition()
        }
        return true
    }

    /**
     * Phase-locks [pB] to [pA] and runs the volume/EQ automation until the zone ends.
     * @return a short description of how it ended, for diagnostics
     */
    private suspend fun runPllLoop(
        pA: ExoPlayer,
        pB: ExoPlayer,
        plan: TransitionPlan,
        config: TransitionConfig,
        gridScalar: Double,
        baseSpeed: Double,
        diagnostics: MixDiagnostics,
    ): String {
        val controller = PhaseController(baseSpeed)
        val startMediaId = pA.currentMediaItem?.mediaId
        val anchorTimeA = TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA)
        val endTimeA = TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA + plan.transitionDurationBeats)
        // Wall-clock budget for the zone (counting only time A is actually playing), so a stalled
        // deck can never keep the loop alive forever.
        val budgetMs = ((endTimeA - anchorTimeA + PREROLL_SECONDS) * 1000 / pA.playbackParameters.speed).toLong() +
                BUDGET_SLACK_MS
        var playingMs = 0L
        var lastTick = System.currentTimeMillis()
        var unmuted = false

        delay(SEEK_SETTLE_MS)

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (pA.isPlaying) playingMs += now - lastTick
            lastTick = now

            // A finished (or auto-advanced to its next item) before the zone ended: hand over now.
            if (pA.playbackState == Player.STATE_ENDED || pA.currentMediaItem?.mediaId != startMediaId) {
                return "a-ended"
            }
            if (playingMs > budgetMs) return "timeout"

            val posA = pA.currentPosition / 1000.0
            val posB = pB.currentPosition / 1000.0
            val elapsedBeatsA = TransitionMath.getBeatForTimestamp(plan.gridA, posA) - plan.anchorBeatA
            val targetBeatB = plan.anchorBeatB + elapsedBeatsA / gridScalar
            val phaseError = targetBeatB - TransitionMath.getBeatForTimestamp(plan.gridB, posB)
            val progress = (elapsedBeatsA / plan.transitionDurationBeats).toFloat()
            _crossfadeProgress.value = progress

            if (progress > 1f) return "completed"

            val stage = if (progress < 0f) PhaseController.Stage.PREROLL else PhaseController.Stage.CROSSFADE

            if (stage == PhaseController.Stage.PREROLL &&
                pB.isPlaying &&
                controller.shouldReseek(phaseError, -elapsedBeatsA, now)
            ) {
                diagnostics.onReseek()
                val lead = SEEK_LEAD_MS / 1000.0
                val leadBeats = TransitionMath.getBeatForTimestamp(plan.gridA, posA + lead) - plan.anchorBeatA
                val timeB = TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB + leadBeats / gridScalar)
                pB.seekTo((timeB * 1000).toLong().coerceAtLeast(0))
                pB.setPlaybackSpeed(controller.appliedSpeed.toFloat())
            } else {
                controller.speedFor(stage, phaseError, now)?.let { speed ->
                    pB.setPlaybackSpeed(speed.toFloat())
                    diagnostics.onSpeedChange()
                }
            }

            if (stage == PhaseController.Stage.PREROLL) {
                pA.volume = outGain
                pB.volume = 0f
            } else {
                if (!unmuted) {
                    unmuted = true
                    diagnostics.onUnmute(phaseError)
                }
                diagnostics.onCrossfadeSample(PhaseController.wrapToNearestBeat(phaseError))

                val stateOut = TransitionMixer.getMixState(
                    "A", progress, config.overlapMode, config.eqMode, config.effectMode,
                    positionA = posA, positionB = posB, beatGridA = plan.gridA, beatGridB = plan.gridB
                )
                val stateIn = TransitionMixer.getMixState(
                    "B", progress, config.overlapMode, config.eqMode, config.effectMode,
                    positionA = posA, positionB = posB, beatGridA = plan.gridA, beatGridB = plan.gridB
                )
                pA.volume = stateOut.volume * outGain
                pB.volume = stateIn.volume * inGain
                ensureEq(pA)?.apply(stateOut, config.effectMode)
                ensureEq(pB)?.apply(stateIn, config.effectMode)
            }

            delay(TICK_MS)
        }
        return "cancelled"
    }

    /** Swaps decks once the incoming deck has taken over. */
    private fun completeTransition() {
        fadeJob = null
        val old = activeDeck
        val new = standbyDeck
        activeDeck = new

        // Audio focus follows the active role.
        old.setAudioAttributes(audioAttributes, false)
        new.setAudioAttributes(audioAttributes, true)

        equalizers[old]?.reset()
        equalizers[new]?.reset()
        new.volume = inGain

        old.stop()
        old.clearMediaItems()
        old.volume = 0f
        old.setPlaybackSpeed(1f)

        _isCrossfading.value = false
        _crossfadeProgress.value = 0f

        onActiveDeckChanged(new)

        // Ease the new active deck back to its natural tempo.
        scheduleSpeedReset()
    }

    /**
     * Ends a running transition immediately in favour of the incoming deck (e.g. the user seeks
     * in the incoming song). No-op when nothing is running.
     */
    fun finishTransitionNow() {
        if (!_isCrossfading.value) return
        fadeJob?.cancel()
        completeTransition()
    }

    /** Abandons a running transition and keeps the outgoing deck playing. */
    fun cancelCrossfade() {
        fadeJob?.cancel()
        fadeJob = null
        val active = activeDeck
        val standby = standbyDeck
        active.volume = outGain
        standby.volume = 0f
        standby.pause()
        standby.setPlaybackSpeed(1f)
        equalizers[active]?.reset()
        equalizers[standby]?.reset()
        _isCrossfading.value = false
        _crossfadeProgress.value = 0f
    }

    private fun scheduleSpeedReset(durationMs: Long = 10_000) {
        speedJob?.cancel()

        val player = activeDeck
        val startSpeed = player.playbackParameters.speed

        // Within 1% of normal: snap straight back instead of running the time-stretcher for 10 s.
        if (abs(startSpeed - 1f) < 0.01f) {
            if (startSpeed != 1f) player.setPlaybackSpeed(1f)
            return
        }

        speedJob = mixerScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + durationMs

            while (isActive && System.currentTimeMillis() < endTime) {
                val progress = (System.currentTimeMillis() - startTime).toFloat() / durationMs
                player.setPlaybackSpeed(startSpeed + (1f - startSpeed) * progress)
                // Coarse steps give the time-stretcher time to settle between changes.
                delay(500)
            }
            player.setPlaybackSpeed(1f)
        }
    }

    // --- Equalizer ---

    private fun ensureEq(player: ExoPlayer): DeckEqualizer? {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return null

        equalizers[player]?.let { existing ->
            if (existing.sessionId == sessionId) return existing
            existing.release()
            equalizers.remove(player)
        }
        return try {
            DeckEqualizer(Equalizer(0, sessionId).apply { enabled = true }, sessionId)
                .also { equalizers[player] = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init EQ", e)
            null
        }
    }

    /**
     * Approximates a DJ mixer's bass kill and low/high-pass filters with the platform's graphic
     * equalizer, writing a band only when its level actually changes (each write is an IPC call).
     */
    private class DeckEqualizer(val equalizer: Equalizer, val sessionId: Int) {
        private val bands = equalizer.numberOfBands.toInt()
        private val minLevel = equalizer.bandLevelRange[0].toInt()
        private val written = IntArray(bands)
        private val target = IntArray(bands)

        fun apply(state: DeckState, effectMode: String) {
            if (bands < 1) return
            target.fill(0)

            // Bass cut on the lowest bands
            val bassCut = (minLevel * (1f - state.bass)).toInt()
            target[0] = bassCut
            if (bands > 1) target[1] = (bassCut * 0.8).toInt()

            val cut = (minLevel * (1f - state.filterHigh)).toInt()
            if (TransitionMixer.isLowPass(effectMode)) {
                target[bands - 1] = cut
                if (bands > 2) target[bands - 2] = (cut * 0.7).toInt()
            } else if (TransitionMixer.isHighPass(effectMode)) {
                // Whichever of bass cut and high-pass is stronger wins on the low bands.
                target[0] = minOf(target[0], cut)
                if (bands > 1) target[1] = minOf(target[1], cut)
                if (bands > 2) target[2] = (cut * 0.5).toInt()
            }
            write()
        }

        fun reset() {
            target.fill(0)
            write()
        }

        private fun write() {
            for (band in 0 until bands) {
                if (abs(target[band] - written[band]) < MIN_LEVEL_STEP_MB && !(target[band] == 0 && written[band] != 0)) continue
                try {
                    equalizer.setBandLevel(band.toShort(), target[band].toShort())
                    written[band] = target[band]
                } catch (_: Exception) {
                    // EQ failures are non-critical
                }
            }
        }

        fun release() {
            try { equalizer.release() } catch (_: Exception) {}
        }
    }

    fun release() {
        mixerScope.cancel()
        equalizers.values.forEach { it.release() }
        equalizers.clear()
        playerA.release()
        playerBOrNull?.release()
    }

    // --- Player Creation ---

    private fun createPlayer(handleAudioFocus: Boolean): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                pcmEncodingRestrictionLifted: Boolean,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // Float output would bypass these processors (and so the time-stretcher).
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

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactoryProvider()))
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()

        // While crossfading, pausing/resuming the active deck (user, focus loss, headphones
        // unplugged) must take the incoming deck with it.
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (_isCrossfading.value && player === activeDeck) {
                    val standby = standbyDeck
                    if (standby.playWhenReady != playWhenReady) standby.playWhenReady = playWhenReady
                }
            }
        })

        onPlayerCreated(player)
        return player
    }

    /**
     * Loads [mediaItem] on the standby deck, paused at [startPositionMs], so the transition can
     * start without buffering.
     */
    fun prepareNext(mediaItem: MediaItem, startPositionMs: Long, speed: Float?) {
        prepareJob?.cancel()
        val deck = standbyDeck

        deck.stop()
        deck.clearMediaItems()
        deck.setMediaItem(mediaItem)
        deck.volume = 0f
        deck.playWhenReady = false
        deck.seekTo(startPositionMs)
        deck.setPlaybackSpeed(speed ?: 1f)
        deck.prepare()

        prepareJob = mixerScope.launch {
            val ready = withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
                while (deck.playbackState != Player.STATE_READY) delay(20)
                true
            }
            if (ready == null) Log.w(TAG, "Standby deck did not become ready within ${PREPARE_TIMEOUT_MS}ms")
        }
    }

    companion object {
        const val PREROLL_SECONDS = 3.0
        private const val TICK_MS = 20L
        private const val SEEK_LEAD_MS = 50L
        private const val SEEK_SETTLE_MS = 50L
        private const val BUDGET_SLACK_MS = 5_000L
        private const val PREPARE_TIMEOUT_MS = 15_000L
        /** Equalizer writes smaller than this (millibels) are skipped. */
        private const val MIN_LEVEL_STEP_MB = 30
    }
}
