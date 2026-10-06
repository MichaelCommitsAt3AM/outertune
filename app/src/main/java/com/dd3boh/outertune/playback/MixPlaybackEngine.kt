package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.daos.TransitionDao
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.TransitionEntity
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.LogicalPlayerState
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.utils.analysis.AudioDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import com.dd3boh.outertune.utils.DebugLog as Log

/**
 * Playback engine for mix-mode playlists: plays the queue on [DeckManager]'s active deck and,
 * for each (current -> next) pair with a saved [TransitionEntity], beatmatches the next song in.
 *
 * Runs entirely on [scope], which must be confined to the main thread (the players' looper).
 */
class MixPlaybackEngine(
    val deckManager: DeckManager,
    private val scope: CoroutineScope,
    private val database: MusicDatabase,
    private val transitionDao: TransitionDao,
    private val queueBoard: QueueBoard,
    /** Final volume for a song when it becomes the active one (normalization × user volume). */
    private val targetGainFor: suspend (songId: String) -> Float,
    private val updateLogicalStateCallback: (LogicalPlayerState) -> Unit,
) : PlaybackEngine {

    private val TAG = "MixPlaybackEngine"

    private val _activePlayer = MutableStateFlow(deckManager.activeDeck)
    override val activePlayer: StateFlow<ExoPlayer> = _activePlayer.asStateFlow()

    override val isCrossfading: StateFlow<Boolean> = deckManager.isCrossfading

    private var pollerJob: Job? = null
    private var loadJob: Job? = null

    /**
     * Lifecycle of a single transition between the current song and [TransitionEntity.toSongId].
     *
     * IDLE ──(transition row found + standby pre-warmed)──▶ PREFETCH
     * PREFETCH ──(plan built, ~15s before exit)──▶ ARMED
     * ARMED ──(PLL fired ~3s before exit)──▶ CROSSFADING   (UI switches to the incoming song at the midpoint)
     * CROSSFADING ──(DeckManager.completeTransition → onActiveDeckChanged)──▶ IDLE
     *
     * Any state ──(seek / manual track change / queue change / repeat-one)──▶ IDLE via [resetMixState].
     */
    private enum class MixPhase { IDLE, PREFETCH, ARMED, CROSSFADING }

    private var phase = MixPhase.IDLE
    private var currentTransitionCache: TransitionEntity? = null
    private var currentTransitionPlan: TransitionPlan? = null
    private var currentTransitionConfig: TransitionConfig? = null
    private var incomingGain = 1f

    /** The song being brought in, and the queue position it was brought in from. */
    private var crossfadeTarget: MediaMetadata? = null
    private var crossfadeFromQueuePos: Int? = null
    /** Whether the UI already shows [crossfadeTarget]. */
    private var switchedToNext = false

    private companion object {
        const val LAZY_LOAD_LEAD_MS = 15_000L
        val PLL_LEAD_MS = (DeckManager.PREROLL_SECONDS * 1000).toLong()
        /** Crossfade progress at which the UI switches to the incoming song. */
        const val UI_SWITCH_PROGRESS = 0.5f
    }

    private var localLogicalState = LogicalPlayerState()

    override fun start() {
        Log.i(TAG, "Starting MixPlaybackEngine Poller")
        startMixPoller()
    }

    override fun destroy() {
        Log.i(TAG, "Destroying MixPlaybackEngine")
        pollerJob?.cancel()
        loadJob?.cancel()
        deckManager.release()
    }

    override fun seekTo(positionMs: Long) {
        if (phase == MixPhase.CROSSFADING && switchedToNext) {
            // The UI already shows the incoming song, so this is a seek within it: finish the
            // transition now (onActiveDeckChanged re-arms) and seek the new active deck.
            deckManager.finishTransitionNow()
            val player = activePlayer.value
            val duration = player.duration.takeIf { it > 0 }
            player.seekTo(if (duration != null) positionMs.coerceIn(0L, duration) else positionMs)
            publishLogicalState(player)
            return
        }

        val state = localLogicalState
        val clampedPosition = if (state.durationMs > 0) positionMs.coerceIn(0L, state.durationMs) else positionMs

        // A seek invalidates any armed / in-flight transition. Tear it down before moving.
        resetMixState()

        activePlayer.value.seekTo(clampedPosition)

        localLogicalState = state.copy(currentPositionMs = clampedPosition, isTransitionActive = false)
        updateLogicalStateCallback(localLogicalState)

        // Re-arm for wherever we landed.
        refreshTransition(activePlayer.value.currentMediaItem?.mediaId)
    }

    /**
     * Abandon any armed or in-flight transition and return to [MixPhase.IDLE].
     * Callers that still expect a transition for the current pair must follow with
     * [refreshTransition] / [loadTransitionOnce].
     */
    private fun resetMixState() {
        loadJob?.cancel()
        if (deckManager.isCrossfading.value) deckManager.cancelCrossfade()
        currentTransitionCache = null
        currentTransitionPlan = null
        currentTransitionConfig = null
        crossfadeTarget = null
        crossfadeFromQueuePos = null
        switchedToNext = false
        phase = MixPhase.IDLE
        lastMixCheckTime = 0L
    }

    private fun switchLogicalToNext(nextSong: MediaMetadata) {
        switchedToNext = true
        val standby = deckManager.standbyDeck
        localLogicalState = LogicalPlayerState(
            activeMetadata = nextSong,
            currentPositionMs = standby.currentPosition,
            durationMs = standby.duration.takeIf { it > 0 } ?: (nextSong.duration * 1000L),
            isTransitionActive = true,
        )
        updateLogicalStateCallback(localLogicalState)
    }

    /** Called by DeckManager once the crossfade is complete and the decks have physically swapped. */
    fun onActiveDeckChanged(newPlayer: ExoPlayer) {
        Log.i(TAG, "Active Deck Changed to: ${if (newPlayer === deckManager.playerA) "A" else "B"}")
        val fromQueuePos = crossfadeFromQueuePos
        _activePlayer.value = newPlayer

        // The transition that just completed is done with; anything armed is stale.
        resetMixState()

        // Advance the queue by exactly one from where the transition started. Using the recorded
        // position keeps this idempotent if the outgoing deck already auto-advanced on its own.
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
            Log.d(TAG, "Advancing queue to $nextIndex")
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

    private fun startMixPoller() {
        pollerJob = scope.launch {
            while (isActive) {
                if (activePlayer.value.isPlaying) {
                    updateLogicalState()
                    checkMixStatus()
                }
                delay(50)
            }
        }
    }

    private fun updateLogicalState() {
        val player = activePlayer.value
        val currentMeta = player.currentMetadata ?: return

        if (phase == MixPhase.CROSSFADING) {
            val target = crossfadeTarget
            if (!switchedToNext && target != null &&
                deckManager.crossfadeProgress.value >= UI_SWITCH_PROGRESS
            ) {
                switchLogicalToNext(target)
                return
            }
            if (switchedToNext) {
                // Show the incoming deck's real position.
                val standby = deckManager.standbyDeck
                localLogicalState = localLogicalState.copy(
                    currentPositionMs = standby.currentPosition,
                    durationMs = standby.duration.takeIf { it > 0 } ?: localLogicalState.durationMs,
                )
                updateLogicalStateCallback(localLogicalState)
                return
            }
        }

        // The outgoing song's logical end is the exit point.
        val logicalDuration = currentTransitionCache?.exitPointMs ?: player.duration
        localLogicalState = LogicalPlayerState(
            activeMetadata = currentMeta,
            currentPositionMs = min(player.currentPosition, logicalDuration),
            durationMs = logicalDuration,
            isTransitionActive = false,
        )
        updateLogicalStateCallback(localLogicalState)
    }

    private var lastMixCheckTime = 0L

    /**
     * Runs on the main thread every ~50ms while the active deck is playing.
     * Advances the transition state machine; DeckManager owns the audio once CROSSFADING.
     */
    private suspend fun checkMixStatus() {
        // Once the crossfade is running the DeckManager drives everything until onActiveDeckChanged().
        if (phase == MixPhase.CROSSFADING) return

        val player = activePlayer.value
        if (!player.isPlaying) return

        // Repeat-one: never transition away from the current song.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (phase != MixPhase.IDLE) resetMixState()
            return
        }

        val transition = currentTransitionCache ?: return
        val currentPosition = player.currentPosition
        val now = System.currentTimeMillis()

        // Adaptive cadence: tighten as we approach the exit point.
        val interval = if (transition.exitPointMs - currentPosition < 10_000) 20L else 500L
        if (now - lastMixCheckTime < interval) return
        lastMixCheckTime = now

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

        // PREFETCH -> ARMED: build the plan ~15s before the exit point (immediately for early exits).
        if (phase == MixPhase.PREFETCH && currentPosition >= transition.exitPointMs - LAZY_LOAD_LEAD_MS) {
            performLazyLoading(transition, nextSong.id) // -> ARMED on success, IDLE on failure
        }

        // ARMED -> CROSSFADING: fire the PLL ~3s before the exit point.
        if (phase == MixPhase.ARMED && currentPosition >= transition.exitPointMs - PLL_LEAD_MS) {
            val plan = currentTransitionPlan
            val config = currentTransitionConfig
            if (plan != null && config != null) {
                val fromQueuePos = queueBoard.getCurrentQueue()?.getQueuePosShuffled()
                if (deckManager.startPllTransition(plan, config, incomingGain)) {
                    phase = MixPhase.CROSSFADING
                    crossfadeTarget = nextSong
                    crossfadeFromQueuePos = fromQueuePos
                    switchedToNext = false
                } else {
                    // Plan was rejected. Let the song play through to its natural end rather than
                    // leaving the UI inconsistent with the audio.
                    Log.w(TAG, "PLL rejected the plan; skipping this transition.")
                    phase = MixPhase.IDLE
                }
            }
        }
    }

    private suspend fun performLazyLoading(transition: TransitionEntity, nextId: String) {
        val currentId = activePlayer.value.currentMediaItem?.mediaId ?: return
        val songA = database.song(currentId).firstOrNull()?.song
        val songB = database.song(nextId).firstOrNull()?.song
        if (songA == null || songB == null) {
            phase = MixPhase.IDLE
            return
        }

        val exitSec = transition.exitPointMs / 1000.0
        val entrySec = transition.entryPointMs / 1000.0
        val durSec = transition.durationMs / 1000.0

        // Full grids: a few hundred beats each, and absolute beat indices avoid window edge cases.
        val (gridA, gridB) = withContext(Dispatchers.IO) { loadGrid(songA) to loadGrid(songB) }
        val gain = targetGainFor(nextId)

        // A seek / track change while we were loading invalidates this work.
        if (phase != MixPhase.PREFETCH) return

        val bpmA = songA.displayBpm ?: 0f
        val bpmB = songB.displayBpm ?: 0f

        if (gridA == null || gridB == null || bpmA <= 0 || bpmB <= 0) {
            // Missing grids / BPM — give up on this pair rather than retrying every tick.
            Log.w(TAG, "Lazy loading failed (missing grids or BPM); transition skipped.")
            phase = MixPhase.IDLE
            return
        }

        currentTransitionConfig = TransitionConfig(
            overlapMode = transition.overlapMode,
            eqMode = transition.eqMode,
            effectMode = transition.effectMode,
            barsCount = (transition.durationBeats ?: 32) / 4
        )

        // Plan v1+: trust the editor's synchronisation contract instead of re-deriving it from BPM.
        val legacySpeedB = (if (transition.syncTempo) bpmA / bpmB else 1f).toDouble()

        currentTransitionPlan = TransitionPlan(
            initialSpeedB = transition.initialSpeedB ?: legacySpeedB,
            gridScalarB = transition.gridScalarB ?: 1.0,
            anchorBeatA = TransitionMath.getBeatForTimestamp(gridA, exitSec),
            anchorBeatB = TransitionMath.getBeatForTimestamp(gridB, entrySec),
            transitionDurationBeats = transition.transitionDurationBeats
                ?: transition.durationBeats?.toDouble()
                ?: (durSec * (bpmA / 60.0)),
            exitPointMs = transition.exitPointMs,
            entryPointMs = transition.entryPointMs,
            durationMs = transition.durationMs,
            gridA = gridA,
            gridB = gridB
        )
        incomingGain = gain
        phase = MixPhase.ARMED
        Log.d(TAG, "Transition armed (persisted=${transition.planVersion != null}).")
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
        // Don't disturb a running crossfade — onActiveDeckChanged() re-arms once it completes.
        if (phase == MixPhase.CROSSFADING) return

        loadJob?.cancel()
        currentTransitionCache = null
        currentTransitionPlan = null
        currentTransitionConfig = null
        phase = MixPhase.IDLE

        if (nextId == null) return

        loadJob = scope.launch {
            val transition = transitionDao.getTransition(currentId, nextId) ?: return@launch
            Log.d(TAG, "Transition found: exit=${transition.exitPointMs}, dur=${transition.durationMs}")

            val nextSong = queueBoard.peekNext()
            if (nextSong == null || nextSong.id != nextId) return@launch

            val songA = database.song(currentId).firstOrNull()?.song
            val songB = database.song(nextId).firstOrNull()?.song
            val bpmA = songA?.displayBpm
            val bpmB = songB?.displayBpm
            val speedRatio = if (transition.syncTempo && bpmA != null && bpmB != null && bpmB > 0) {
                transition.initialSpeedB?.toFloat() ?: (bpmA / bpmB)
            } else null

            // Load the standby deck a few seconds before the entry point, paused, so the
            // transition starts from buffered audio.
            val startMs = (transition.entryPointMs - PLL_LEAD_MS).coerceAtLeast(0)
            if (deckManager.standbyDeck.isPlaying) return@launch
            deckManager.prepareNext(nextSong.toMediaItem(), startMs, speedRatio)

            currentTransitionCache = transition
            phase = MixPhase.PREFETCH
        }
    }

    private fun loadGrid(song: SongEntity): List<Double>? {
        if (song.beatGridPath != null) {
            try {
                return AudioDecoder.loadBeatGrid(File(song.beatGridPath))?.map { it.toDouble() / 1000.0 }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load beat grid for ${song.id}", e)
                return null
            }
        }
        return generateSimpleGrid(song)
    }

    private fun generateSimpleGrid(song: SongEntity): List<Double>? {
        val bpm = song.displayBpm ?: return null
        if (bpm <= 0.1f || song.duration <= 0) return null

        val beatDur = 60.0 / bpm
        val grid = mutableListOf<Double>()
        var t = (song.firstBeatMs ?: 0L) / 1000.0
        while (t < song.duration) {
            grid.add(t)
            t += beatDur
        }
        return grid.ifEmpty { null }
    }
}
