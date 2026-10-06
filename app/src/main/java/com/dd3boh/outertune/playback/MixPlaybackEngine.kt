package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.daos.TransitionDao
import com.dd3boh.outertune.db.entities.TransitionEntity
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.LogicalPlayerState
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.transition.engine.BeatGridRepository
import com.dd3boh.outertune.utils.LoudnessNormalization
import com.dd3boh.outertune.transition.engine.DeckFactory
import com.dd3boh.outertune.transition.engine.DeckPair
import com.dd3boh.outertune.transition.engine.ProcessorDeckEffects
import com.dd3boh.outertune.transition.engine.MixTuning
import com.dd3boh.outertune.transition.engine.TransitionRenderer
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import com.dd3boh.outertune.utils.DebugLog as Log

/**
 * Playback engine for mix-mode playlists: plays the queue on the active deck of a [DeckPair] and,
 * for each (current -> next) pair with a saved [TransitionEntity], beatmatches the next song in
 * with the shared [TransitionRenderer] (the same engine the editor previews with).
 *
 * This class is the single owner of transition state. Runs on [scope], which must be confined to
 * the main thread (the players' looper).
 */
class MixPlaybackEngine(
    deckFactory: DeckFactory,
    private val scope: CoroutineScope,
    private val database: MusicDatabase,
    private val transitionDao: TransitionDao,
    private val queueBoard: QueueBoard,
    private val beatGrids: BeatGridRepository,
    /** Loudness-normalization gain for a song (without the user's volume). */
    private val normalizationFor: suspend (songId: String) -> Float,
    /** The user's volume; each deck plays at this × its song's normalization. */
    private val userVolume: StateFlow<Float>,
    private val updateLogicalStateCallback: (LogicalPlayerState) -> Unit,
) : PlaybackEngine {

    private val TAG = "MixPlaybackEngine"

    private val decks = DeckPair(deckFactory)
    private val renderer = TransitionRenderer(ProcessorDeckEffects(deckFactory))

    private val _activePlayer = MutableStateFlow(decks.active)
    override val activePlayer: StateFlow<ExoPlayer> = _activePlayer.asStateFlow()

    private val _isCrossfading = MutableStateFlow(false)
    override val isCrossfading: StateFlow<Boolean> = _isCrossfading.asStateFlow()

    private var pollerJob: Job? = null
    private var loadJob: Job? = null
    private var crossfadeJob: Job? = null

    /**
     * Lifecycle of a single transition between the current song and [TransitionEntity.toSongId].
     *
     * IDLE ──(transition row found + standby pre-warmed)──▶ PREFETCH
     * PREFETCH ──(plan built, ~15s before exit)──▶ ARMED
     * ARMED ──(renderer started ~3s before exit)──▶ CROSSFADING   (UI switches to the incoming song at the midpoint)
     * CROSSFADING ──(renderer finished → decks swapped)──▶ IDLE
     *
     * Any state ──(seek / manual track change / queue change / repeat-one)──▶ IDLE via [resetMixState].
     */
    private enum class MixPhase { IDLE, PREFETCH, ARMED, CROSSFADING }

    private var phase = MixPhase.IDLE
    private var currentTransitionCache: TransitionEntity? = null
    private var currentTransitionPlan: TransitionPlan? = null
    private var currentTransitionConfig: TransitionConfig? = null

    /** Normalization gains of songs seen so far, by song id. */
    private val normalization = HashMap<String, Float>()
    /** Normalization of the song the armed transition brings in. */
    private var armedIncomingNorm = 1f
    private var crossfadeProgress = 0f

    /** The song being brought in, and the queue position it was brought in from. */
    private var crossfadeTarget: MediaMetadata? = null
    private var crossfadeFromQueuePos: Int? = null
    /** Whether the UI already shows [crossfadeTarget]. */
    private var switchedToNext = false

    private val prerollLeadMs = (MixTuning.PREROLL_SECONDS * 1000).toLong()

    private var localLogicalState = LogicalPlayerState()

    override fun start() {
        Log.i(TAG, "Starting MixPlaybackEngine Poller")
        decks.onIsPlayingChanged = ::wakePoller
        decks.onMediaItemTransition = { player -> if (player === decks.active) applyActiveVolume() }
        scope.launch { userVolume.collect { applyActiveVolume() } }
        startMixPoller()
    }

    override fun destroy() {
        Log.i(TAG, "Destroying MixPlaybackEngine")
        pollerJob?.cancel()
        loadJob?.cancel()
        crossfadeJob?.cancel()
        decks.release()
    }

    override fun seekTo(positionMs: Long) {
        if (phase == MixPhase.CROSSFADING && switchedToNext) {
            // The UI already shows the incoming song, so this is a seek within it: finish the
            // transition now and seek the new active deck.
            finishCrossfade()
            val player = decks.active
            val duration = player.duration.takeIf { it > 0 }
            player.seekTo(if (duration != null) positionMs.coerceIn(0L, duration) else positionMs)
            publishLogicalState(player)
            return
        }

        val state = localLogicalState
        val clampedPosition = if (state.durationMs > 0) positionMs.coerceIn(0L, state.durationMs) else positionMs

        // A seek invalidates any armed / in-flight transition. Tear it down before moving.
        resetMixState()

        decks.active.seekTo(clampedPosition)

        localLogicalState = state.copy(currentPositionMs = clampedPosition, isTransitionActive = false)
        updateLogicalStateCallback(localLogicalState)

        // Re-arm for wherever we landed.
        refreshTransition(decks.active.currentMediaItem?.mediaId)
    }

    /**
     * Abandon any armed or in-flight transition and return to [MixPhase.IDLE].
     * Callers that still expect a transition for the current pair must follow with
     * [refreshTransition] / [loadTransitionOnce].
     */
    private fun resetMixState() {
        loadJob?.cancel()
        if (crossfadeJob != null) cancelCrossfade()
        currentTransitionCache = null
        currentTransitionPlan = null
        currentTransitionConfig = null
        crossfadeTarget = null
        crossfadeFromQueuePos = null
        switchedToNext = false
        phase = MixPhase.IDLE
    }

    // --- Crossfade ---

    private fun startCrossfade(plan: TransitionPlan, config: TransitionConfig, nextSong: MediaMetadata) {
        val outgoing = decks.active
        val incoming = decks.standby
        val outgoingNorm = normalizationOf(outgoing)
        val incomingNorm = normalization[nextSong.id] ?: armedIncomingNorm
        crossfadeProgress = 0f
        crossfadeTarget = nextSong
        crossfadeFromQueuePos = queueBoard.getCurrentQueue()?.getQueuePosShuffled()
        switchedToNext = false
        phase = MixPhase.CROSSFADING
        decks.linked = true
        _isCrossfading.value = true

        crossfadeJob = scope.launch {
            val result = renderer.run(
                outgoing = outgoing,
                incoming = incoming,
                plan = plan,
                config = { config },
                outgoingGain = { outgoingNorm * userVolume.value },
                incomingGain = { incomingNorm * userVolume.value },
                diagnosticsLabel = "playlist",
            ) { frame ->
                crossfadeProgress = frame.progress
                val target = crossfadeTarget
                if (!switchedToNext && target != null && frame.progress >= MixTuning.UI_SWITCH_PROGRESS) {
                    switchLogicalToNext(target)
                }
            }
            Log.d(TAG, "Transition ended: $result")
            // Whatever ended it, B is already playing; hand over to it.
            crossfadeJob = null
            completeCrossfade()
        }
    }

    /** Ends a running transition in favour of the incoming deck. */
    private fun finishCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        completeCrossfade()
    }

    private fun completeCrossfade() {
        decks.swap()
        _isCrossfading.value = false
        applyActiveVolume()
        onActiveDeckChanged(decks.active)
    }

    /** Abandons a running transition and keeps the outgoing deck playing. */
    private fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        decks.linked = false
        decks.standby.apply {
            volume = 0f
            pause()
            setPlaybackSpeed(1f)
        }
        _isCrossfading.value = false
        applyActiveVolume()
    }

    // --- Volume ---

    /**
     * Sets the active deck's volume to the user's volume × its song's normalization. The engine is
     * the only thing that sets deck volumes; during a transition the renderer does, through the
     * same gains.
     */
    private fun applyActiveVolume() {
        if (_isCrossfading.value) return
        val player = decks.active
        val songId = player.currentMediaItem?.mediaId ?: return
        val norm = normalization[songId]
        if (norm != null) {
            player.volume = norm * userVolume.value
        } else {
            scope.launch {
                val gain = normalizationFor(songId)
                if (decks.active.currentMediaItem?.mediaId != songId || _isCrossfading.value) return@launch
                decks.active.volume = gain * userVolume.value
                // A pending value (loudness not fetched yet) is re-read next time instead.
                if (gain != LoudnessNormalization.PENDING_FACTOR) normalization[songId] = gain
            }
        }
    }

    /** The normalization the deck's current song plays at (cached when it became active). */
    private fun normalizationOf(player: ExoPlayer): Float {
        val id = player.currentMediaItem?.mediaId
        return id?.let { normalization[it] } ?: (player.volume / userVolume.value.coerceAtLeast(0.001f))
    }

    private fun switchLogicalToNext(nextSong: MediaMetadata) {
        switchedToNext = true
        val standby = decks.standby
        localLogicalState = LogicalPlayerState(
            activeMetadata = nextSong,
            currentPositionMs = standby.currentPosition,
            durationMs = standby.duration.takeIf { it > 0 } ?: (nextSong.duration * 1000L),
            isTransitionActive = true,
        )
        updateLogicalStateCallback(localLogicalState)
    }

    /** After a deck swap: advance the queue and arm the next pair. */
    private fun onActiveDeckChanged(newPlayer: ExoPlayer) {
        val fromQueuePos = crossfadeFromQueuePos
        _activePlayer.value = newPlayer

        // The transition that just completed is done with; anything armed is stale.
        resetMixState()

        queueBoard.getCurrentQueue()?.let { q ->
            // Find the incoming song after the position the transition started from (normally
            // the very next item, unless the queue was edited mid-transition).
            val items = q.getCurrentQueueShuffled()
            val from = (fromQueuePos ?: q.getQueuePosShuffled()) + 1
            val songId = newPlayer.currentMediaItem?.mediaId
            val nextIndex = (from until items.size).firstOrNull { items[it].id == songId }
            if (nextIndex == null) {
                Log.w(TAG, "Incoming song $songId is not in the queue after $from; leaving the queue as is")
                return@let
            }
            queueBoard.setCurrQueuePosIndex(nextIndex)
            // The incoming deck was loaded with just this one song; give it the rest of the queue
            // (seamlessly, around the playing item) so songs without a transition still advance.
            queueBoard.setCurrQueue()
        }

        publishLogicalState(newPlayer)

        // Pre-warm the transition for the new (current -> next) pair.
        refreshTransition(newPlayer.currentMediaItem?.mediaId)
    }

    private fun publishLogicalState(player: ExoPlayer) {
        player.currentMetadata?.let { metadata ->
            localLogicalState = LogicalPlayerState(
                activeMetadata = metadata,
                currentPositionMs = player.currentPosition,
                durationMs = player.duration,
                isTransitionActive = false,
            )
            updateLogicalStateCallback(localLogicalState)
        }
    }

    // --- State machine ---

    /** Nudges the poller to re-evaluate now (state changed, seek, play/pause). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private fun wakePoller() {
        wake.trySend(Unit)
    }

    /**
     * Runs the state machine only when something can happen: at the arm point, at the start of
     * the preroll, or when [wakePoller] signals a change. In between (most of every song) it
     * sleeps.
     */
    private fun startMixPoller() {
        pollerJob = scope.launch {
            while (isActive) {
                val sleepMs = nextCheckDelayMs()
                if (sleepMs == null) wake.receive() else withTimeoutOrNull(sleepMs) { wake.receive() }
                if (decks.active.isPlaying) {
                    updateLogicalState()
                    checkMixStatus()
                }
            }
        }
    }

    /** Time until the state machine next has something to do, or null to wait for a signal. */
    private fun nextCheckDelayMs(): Long? {
        val player = decks.active
        if (!player.isPlaying) return null
        val transition = currentTransitionCache ?: return null
        val target = when (phase) {
            MixPhase.PREFETCH -> transition.exitPointMs - MixTuning.ARM_LEAD_MS
            MixPhase.ARMED -> transition.exitPointMs - prerollLeadMs
            // The renderer drives a running transition; IDLE waits for a transition to load.
            MixPhase.IDLE, MixPhase.CROSSFADING -> return null
        }
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        return ((target - player.currentPosition) / speed).toLong().coerceIn(MixTuning.POLLER_MIN_WAKE_MS, MixTuning.POLLER_MAX_SLEEP_MS)
    }

    /**
     * Publishes the logical state when something the UI shows changes (song, duration, the
     * midpoint switch). Position is not pushed; the UI reads [logicalPositionMs] while visible.
     */
    private fun updateLogicalState() {
        val player = decks.active
        val currentMeta = player.currentMetadata ?: return

        if (phase == MixPhase.CROSSFADING && !switchedToNext) {
            val target = crossfadeTarget
            if (target != null && crossfadeProgress >= MixTuning.UI_SWITCH_PROGRESS) {
                switchLogicalToNext(target)
                return
            }
        }
        if (phase == MixPhase.CROSSFADING && switchedToNext) {
            publish(localLogicalState.copy(durationMs = logicalDurationMs()))
            return
        }

        publish(
            LogicalPlayerState(
                activeMetadata = currentMeta,
                currentPositionMs = logicalPositionMs(),
                durationMs = logicalDurationMs(),
                isTransitionActive = false,
            )
        )
    }

    /** Sends [state] on if it differs from the last one in anything but position. */
    private fun publish(state: LogicalPlayerState) {
        val changed = state.copy(currentPositionMs = 0) != localLogicalState.copy(currentPositionMs = 0)
        localLogicalState = state
        if (changed) updateLogicalStateCallback(state)
    }

    override fun logicalPositionMs(): Long {
        if (phase == MixPhase.CROSSFADING && switchedToNext) return decks.standby.currentPosition
        val position = decks.active.currentPosition
        val exit = currentTransitionCache?.exitPointMs ?: return position
        return min(position, exit)
    }

    override fun logicalDurationMs(): Long {
        if (phase == MixPhase.CROSSFADING && switchedToNext) {
            return decks.standby.duration.takeIf { it > 0 } ?: localLogicalState.durationMs
        }
        return currentTransitionCache?.exitPointMs ?: decks.active.duration
    }

    /** Advances the state machine; called by the poller when something may be due. */
    private suspend fun checkMixStatus() {
        // Once the crossfade is running the renderer drives the audio until it completes.
        if (phase == MixPhase.CROSSFADING) return

        val player = decks.active
        if (!player.isPlaying) return

        // Repeat-one: never transition away from the current song.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (phase != MixPhase.IDLE) resetMixState()
            return
        }

        val transition = currentTransitionCache ?: return
        val currentPosition = player.currentPosition

        // Don't transition if the exit point is effectively the end of the file.
        val realDuration = player.duration
        if (realDuration > 0 && transition.exitPointMs > realDuration - 500) return

        // The queued "next" song must still be the one this transition targets.
        val nextSong = queueBoard.peekNext()
        if (nextSong == null || nextSong.id != transition.toSongId) {
            val currentId = player.currentMediaItem?.mediaId ?: return
            resetMixState()
            loadTransitionOnce(currentId, nextSong?.id)
            return
        }

        // PREFETCH -> ARMED: build the plan ahead of the exit point (immediately for early exits).
        if (phase == MixPhase.PREFETCH && currentPosition >= transition.exitPointMs - MixTuning.ARM_LEAD_MS) {
            performLazyLoading(transition, nextSong.id) // -> ARMED on success, IDLE on failure
        }

        // ARMED -> CROSSFADING: start the renderer at the start of the preroll.
        if (phase == MixPhase.ARMED && currentPosition >= transition.exitPointMs - prerollLeadMs) {
            val plan = currentTransitionPlan
            val config = currentTransitionConfig
            if (plan != null && config != null) startCrossfade(plan, config, nextSong)
        }
    }

    private suspend fun performLazyLoading(transition: TransitionEntity, nextId: String) {
        val currentId = decks.active.currentMediaItem?.mediaId ?: return
        val songA = database.song(currentId).firstOrNull()?.song
        val songB = database.song(nextId).firstOrNull()?.song
        if (songA == null || songB == null) {
            phase = MixPhase.IDLE
            return
        }

        val gridA = beatGrids.grid(songA)
        val gridB = beatGrids.grid(songB)
        armedIncomingNorm = normalizationFor(nextId)
        if (armedIncomingNorm != LoudnessNormalization.PENDING_FACTOR) normalization[nextId] = armedIncomingNorm

        // A seek / track change while we were loading invalidates this work.
        if (phase != MixPhase.PREFETCH) return

        val bpmA = songA.displayBpm ?: 0f
        val bpmB = songB.displayBpm ?: 0f
        if (gridA.isNullOrEmpty() || gridB.isNullOrEmpty() || bpmA <= 0 || bpmB <= 0) {
            // Missing grids / BPM — give up on this pair rather than retrying every tick.
            Log.w(TAG, "Lazy loading failed (missing grids or BPM); transition skipped.")
            phase = MixPhase.IDLE
            return
        }

        currentTransitionConfig = TransitionConfig(
            overlapMode = transition.overlapMode,
            eqMode = transition.eqMode,
            effectMode = transition.effectMode,
            barsCount = transition.bars,
        )

        // Plan v1+: trust the editor's synchronisation contract instead of re-deriving it from BPM.
        val legacySpeedB = (if (transition.syncTempo) bpmA / bpmB else 1f).toDouble()
        val anchorBeatA = TransitionMath.getBeatForTimestamp(gridA, transition.exitPointMs / 1000.0)

        currentTransitionPlan = TransitionPlan(
            initialSpeedB = transition.initialSpeedB ?: legacySpeedB,
            gridScalarB = transition.gridScalarB ?: 1.0,
            anchorBeatA = anchorBeatA,
            anchorBeatB = TransitionMath.getBeatForTimestamp(gridB, transition.entryPointMs / 1000.0),
            transitionDurationBeats = transition.transitionDurationBeats,
            exitPointMs = transition.exitPointMs,
            entryPointMs = transition.entryPointMs,
            durationMs = ((TransitionMath.getTimestampForBeat(gridA, anchorBeatA + transition.transitionDurationBeats) -
                    transition.exitPointMs / 1000.0) * 1000).toLong(),
            gridA = gridA,
            gridB = gridB
        )
        phase = MixPhase.ARMED
        wakePoller()
        Log.d(TAG, "Transition armed (planVersion=${transition.planVersion}).")
    }

    /**
     * Called by MusicService on track change (and internally after a deck swap / seek).
     * Loads the transition row for the (currentId -> next) pair and pre-warms the standby deck.
     */
    fun refreshTransition(currentId: String?) {
        if (currentId == null) return
        scope.launch {
            loadTransitionOnce(currentId, queueBoard.peekNext()?.id)
        }
    }

    private fun loadTransitionOnce(currentId: String, nextId: String?) {
        // Don't disturb a running crossfade — it re-arms once it completes.
        if (phase == MixPhase.CROSSFADING) return

        loadJob?.cancel()
        currentTransitionCache = null
        currentTransitionPlan = null
        currentTransitionConfig = null
        phase = MixPhase.IDLE

        if (nextId == null) return

        loadJob = scope.launch {
            val transition = transitionDao.getTransition(currentId, nextId) ?: return@launch

            val nextSong = queueBoard.peekNext()
            if (nextSong == null || nextSong.id != nextId) return@launch

            // Load the standby deck a few seconds before the entry point, paused, so the
            // transition starts from buffered audio.
            val startMs = (transition.entryPointMs - prerollLeadMs).coerceAtLeast(0)
            val speed = transition.initialSpeedB?.toFloat() ?: 1f
            decks.prepareStandby(nextSong.toMediaItem(), startMs, speed)

            currentTransitionCache = transition
            phase = MixPhase.PREFETCH
            wakePoller()
        }
    }
}
