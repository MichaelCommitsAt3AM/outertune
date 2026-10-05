package com.dd3boh.outertune.playback

import com.dd3boh.outertune.utils.DebugLog as Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.daos.TransitionDao
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.TransitionEntity
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.analysis.AudioDecoder
import java.io.File
import kotlin.math.min
import kotlin.math.pow
import com.dd3boh.outertune.models.LogicalPlayerState // Import LogicalPlayerState

class MixPlaybackEngine(
    val deckManager: DeckManager, // Owns DeckManager
    private val scope: CoroutineScope, // Service scope for background tasks
    private val database: MusicDatabase,
    private val transitionDao: TransitionDao,
    private val queueBoard: QueueBoard, // Needs access to queue for next/prev
    private val updateLogicalStateCallback: (LogicalPlayerState) -> Unit // Callback to update service state
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
     * ARMED ──(PLL fired ~3s before exit)──▶ CROSSFADING   (UI switches to the incoming song here)
     * CROSSFADING ──(DeckManager.completeTransition → onActiveDeckChanged)──▶ IDLE
     *
     * Any state ──(seek / manual track change / queue change / repeat-one)──▶ IDLE via [resetMixState].
     */
    private enum class MixPhase { IDLE, PREFETCH, ARMED, CROSSFADING }

    // Mix State
    @Volatile private var phase = MixPhase.IDLE
    private var currentTransitionCache: TransitionEntity? = null
    private var currentTransitionPlan: TransitionPlan? = null
    private var currentTransitionConfig: TransitionConfig? = null

    private companion object {
        const val LAZY_LOAD_LEAD_MS = 15_000L
        const val PLL_LEAD_MS = 3_000L
    }

    // Initialize logical state locally
    private var localLogicalState = LogicalPlayerState()

    // DeckManager is constructed in MusicService.playQueue() and handed to this engine.
    // Its onActiveDeckChanged callback is wired to forward into onActiveDeckChanged() below.

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
        val state = localLogicalState
        val logicalDuration = state.durationMs

        val clampedPosition = if (logicalDuration > 0) {
            positionMs.coerceIn(0L, logicalDuration)
        } else {
            positionMs
        }

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
        phase = MixPhase.IDLE
        lastMixCheckTime = 0L
    }

    private fun switchLogicalToNext(nextSong: MediaMetadata) {
        localLogicalState = LogicalPlayerState(
            activeMetadata = nextSong,
            currentPositionMs = 0L,
            durationMs = nextSong.duration * 1000L,
            isTransitionActive = true,
        )
        updateLogicalStateCallback(localLogicalState)
    }

    /** Called by DeckManager once the crossfade is complete and the decks have physically swapped. */
    fun onActiveDeckChanged(newPlayer: ExoPlayer) {
        Log.i(TAG, "Active Deck Changed to: ${if (newPlayer == deckManager.playerA) "A" else "B"}")
        _activePlayer.value = newPlayer

        // The transition that just completed is done with; anything armed is stale.
        resetMixState()

        // Advance the queue by exactly one (queue-position based, not deck media-item index).
        queueBoard.getCurrentQueue()?.let { q ->
            val nextIndex = q.queuePos + 1
            Log.d(TAG, "Advancing queue ${q.queuePos} -> $nextIndex")
            queueBoard.setCurrQueuePosIndex(nextIndex)
        }

        // Finalise the logical state on the now-active (incoming) song.
        newPlayer.currentMetadata?.let { newMetadata ->
            localLogicalState = LogicalPlayerState(
                activeMetadata = newMetadata,
                currentPositionMs = newPlayer.currentPosition,
                durationMs = newPlayer.duration,
                isTransitionActive = false,
            )
            updateLogicalStateCallback(localLogicalState)
        }

        // Pre-warm the transition for the new (current -> next) pair.
        refreshTransition(newPlayer.currentMediaItem?.mediaId)
    }
    
    // --- Logic Moved from MusicService ---

    private fun startMixPoller() {
        pollerJob = scope.launch {
            while (isActive) {
                if (activePlayer.value.isPlaying) {
                    // Player reads + DeckManager calls are confined to the deck (main) thread.
                    // TODO(phase-later): move decks to a dedicated audio looper and drop this hop.
                    withContext(Dispatchers.Main) {
                        updateLogicalState()
                        checkMixStatus()
                    }
                }
                delay(50)
            }
        }
    }

    private fun updateLogicalState() {
        val player = activePlayer.value
        val currentMeta = player.currentMetadata ?: return
        val realPos = player.currentPosition
        val realDur = player.duration

        // 1. Determine Logical Duration
        val logicalDuration = currentTransitionCache?.exitPointMs ?: realDur

        // 2. Determine Logical Position
        val logicalPos = min(realPos, logicalDuration)

        // 3. Update State
        if (localLogicalState.activeMetadata?.id == currentMeta.id) {
            localLogicalState = localLogicalState.copy(
                currentPositionMs = logicalPos,
                durationMs = logicalDuration,
                isTransitionActive = false
            )
        } else if (localLogicalState.isTransitionActive) {
             // In Transition -> Show Standby Position
            val standbyPos = deckManager.standbyDeck.currentPosition
            val standbyDur = deckManager.standbyDeck.duration
            localLogicalState = localLogicalState.copy(
                currentPositionMs = standbyPos,
                durationMs = if (standbyDur > 0) standbyDur else localLogicalState.durationMs
            )
        } else {
             // Normal Playback (Metadata changed manually)
             localLogicalState = LogicalPlayerState(
                activeMetadata = currentMeta,
                currentPositionMs = logicalPos,
                durationMs = logicalDuration,
                isTransitionActive = false
            )
        }
        updateLogicalStateCallback(localLogicalState)
    }

    private var lastMixCheckTime = 0L

    /**
     * Runs on the deck (main) thread every ~50ms while the active deck is playing.
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

        // PREFETCH -> ARMED: build the plan ~15s before the exit point.
        if (phase == MixPhase.PREFETCH &&
            transition.exitPointMs > LAZY_LOAD_LEAD_MS &&
            currentPosition >= transition.exitPointMs - LAZY_LOAD_LEAD_MS
        ) {
            performLazyLoading(transition, nextSong.id) // -> ARMED on success, IDLE on failure
        }

        // ARMED -> CROSSFADING: fire the PLL ~3s before the exit point and switch the UI early.
        if (phase == MixPhase.ARMED && currentPosition >= transition.exitPointMs - PLL_LEAD_MS) {
            val plan = currentTransitionPlan
            val config = currentTransitionConfig
            if (plan != null && config != null) {
                deckManager.startPllTransition(plan, transition.durationMs, config)
                if (deckManager.isCrossfading.value) {
                    phase = MixPhase.CROSSFADING
                    switchLogicalToNext(nextSong)
                } else {
                    // Plan was rejected (missing grids / invalid anchors). Let the song play through
                    // to its natural end rather than leaving the UI inconsistent with the audio.
                    Log.w(TAG, "PLL rejected the plan; skipping this transition.")
                    phase = MixPhase.IDLE
                }
            }
        }
    }

    private suspend fun performLazyLoading(transition: TransitionEntity, nextId: String) {
        val currentId = activePlayer.value.currentMediaItem?.mediaId
        
        if (currentId != null) {
            val songA = database.song(currentId).firstOrNull()?.song
            val songB = database.song(nextId).firstOrNull()?.song
            
            if (songA != null && songB != null) {
                 val exitSec = transition.exitPointMs / 1000.0
                 val entrySec = transition.entryPointMs / 1000.0
                 val durSec = transition.durationMs / 1000.0
                 val preroll = 5.0
                 
                 // Load Partial Grids
                 // Grid A: Exit - 10s to Exit + Duration + 5s
                 val startA = ((exitSec - 10.0).coerceAtLeast(0.0) * 1000).toLong()
                 val endA = ((exitSec + durSec + preroll) * 1000).toLong()
                 
                 // Grid B: Entry - 5s to Entry + Duration + 10s
                 val startB = ((entrySec - preroll).coerceAtLeast(0.0) * 1000).toLong()
                 val endB = ((entrySec + durSec + 10.0) * 1000).toLong()
            
                 val (gridA, gridB) = withContext(Dispatchers.IO) {
                     val gA = loadGrid(songA, startA, endA)
                     val gB = loadGrid(songB, startB, endB)
                     gA to gB
                 }

                 // A seek / track change while we were loading invalidates this work.
                 if (phase != MixPhase.PREFETCH) return

                 val bpmA = songA.displayBpm ?: 0f
                 val bpmB = songB.displayBpm ?: 0f

                 if (gridA != null && gridB != null && bpmA > 0 && bpmB > 0) {
                     currentTransitionConfig = TransitionConfig(
                         overlapMode = transition.overlapMode,
                         eqMode = transition.eqMode,
                         effectMode = transition.effectMode,
                         barsCount = (transition.durationBeats ?: 32) / 4
                     )
                     
                     // Anchor beats are grid-window dependent, so derive them from the persisted
                     // absolute exit/entry timestamps against whatever grid slice we just loaded.
                     val exitBeatA = TransitionMath.getBeatForTimestamp(gridA, exitSec)
                     val entryBeatB = TransitionMath.getBeatForTimestamp(gridB, entrySec)

                     // Plan v1+: trust the editor's synchronisation contract instead of re-deriving it
                     // from BPM (which ignored interval-match and could disagree with the preview).
                     val hasPlan = transition.planVersion != null
                     val legacySpeedB = (if (transition.syncTempo && bpmB > 0) bpmA / bpmB else 1f).toDouble()

                     currentTransitionPlan = TransitionPlan(
                         initialSpeedB = transition.initialSpeedB ?: legacySpeedB,
                         gridScalarB = transition.gridScalarB ?: 1.0,
                         anchorBeatA = exitBeatA,
                         anchorBeatB = entryBeatB,
                         transitionDurationBeats = transition.transitionDurationBeats
                             ?: (durSec * (bpmA / 60.0)),
                         exitPointMs = transition.exitPointMs,
                         entryPointMs = transition.entryPointMs,
                         durationMs = transition.durationMs,
                         gridA = gridA,
                         gridB = gridB
                     )
                     phase = MixPhase.ARMED
                     Log.d(TAG, "Transition armed (persisted=$hasPlan).")
                 } else {
                     // Missing grids / BPM — give up on this pair rather than retrying every tick.
                     Log.w(TAG, "Lazy loading failed (missing grids or BPM); transition skipped.")
                     phase = MixPhase.IDLE
                 }
            }
        }
    }

    /**
     * Called by MusicService on track change (and internally after a deck swap / seek).
     * Loads the transition row for the (currentId -> next) pair and pre-warms the standby deck.
     *
     * Per-song loudness normalisation for mix mode flows: [loadTransitionOnce] computes the
     * incoming song's factor and hands it to [DeckManager.setStandbyVolumeMultiplier]; the deck
     * swap in DeckManager.completeTransition carries it to the active deck; from then on
     * MusicService's normalizeFactor flow owns the active player's gain.
     */
    fun refreshTransition(currentId: String?) {
        if (currentId == null) return
        scope.launch {
            val nextSong = queueBoard.peekNext()
            loadTransitionOnce(currentId, nextSong?.id)
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
             val transition = transitionDao.getTransition(currentId, nextId)
             currentTransitionCache = transition

             if (transition != null) {
                 // Pre-warm logic
                 // ...
                     Log.d(TAG, "Transition Found! Exit=${transition.exitPointMs}, Dur=${transition.durationMs}")

                     // --- CHECK FILE EXISTENCE (Lightweight) ---
                     val songA = database.song(currentId).firstOrNull()?.song
                     val songB = database.song(nextId).firstOrNull()?.song
                     
                     if (songA != null && songB != null) {
                          val hasGridA = songA.beatGridPath != null && java.io.File(songA.beatGridPath).exists()
                          val hasGridB = songB.beatGridPath != null && java.io.File(songB.beatGridPath).exists()
                          
                          if (!hasGridA || !hasGridB) {
                               // Try Simple Grid?
                               if (songA.displayBpm == null || songB.displayBpm == null) {
                                   Log.w(TAG, "Transition exists but missing BeatGrids/BPM. Optimistic monitoring.")
                               }
                          }
                     }
     
                     // Pre-warm the secondary deck (player only, no beats yet)
                     val nextSong = queueBoard.peekNext() 
                     
                     if (nextSong != null && nextSong.id == nextId) {
                         val bpmA = songA?.displayBpm ?: 120f 
                         val bpmB = songB?.displayBpm ?: 120f
                         val speedRatio = if (transition.syncTempo && bpmB > 0) bpmA / bpmB else null
                         
                         // Incoming-song loudness normalisation for the crossfade.
                         val nextFormat = database.format(nextId).firstOrNull()
                         val nextNorm = if (nextFormat?.loudnessDb != null) {
                             min(10f.pow(-nextFormat.loudnessDb.toFloat() / 20), 1f)
                         } else 1f

                         val mediaItem = nextSong.toMediaItem()
                         
                         // Approximate start time (Entry Point - 3s) since we don't have accurate grid yet
                         val startMs = (transition.entryPointMs - 3000).coerceAtLeast(0)
     
                         withContext(Dispatchers.Main) {
                             if (!deckManager.standbyDeck.isPlaying) {
                                 deckManager.prepareNext(mediaItem, startMs, speedRatio)
                                 deckManager.setStandbyVolumeMultiplier(nextNorm)
                             }
                         }

                         // Standby deck warmed; the state machine can now arm on approach.
                         if (phase == MixPhase.IDLE) phase = MixPhase.PREFETCH
                     }
             }
        }
    }
    
    // Copied from MusicService
    private fun loadGrid(song: SongEntity, rangeStart: Long? = null, rangeEnd: Long? = null): List<Double>? {
         if (song.beatGridPath != null) {
             try {
                 val loaded = AudioDecoder.loadBeatGrid(File(song.beatGridPath), rangeStart, rangeEnd)
                 return loaded?.map { it.toDouble() / 1000.0 }
             } catch (e: Exception) { return null }
         }
         return generateSimpleGrid(song)
    }

    private fun generateSimpleGrid(song: SongEntity): List<Double>? {
        val bpm = song.displayBpm ?: 0f
        val firstBeat = (song.firstBeatMs ?: 0L) / 1000.0
        val beatDur = 60.0 / bpm

        if (bpm <= 0.1f) return null

        val dbDuration = if (song.duration > 0) song.duration.toDouble() else 0.0
        
        if (dbDuration <= 0.1) return null

        val grid = mutableListOf<Double>()
        var t = firstBeat
        if (beatDur <= 0.0) return null

        while (t < dbDuration) {
            grid.add(t)
            t += beatDur
        }
        
        if (grid.isEmpty()) return null
        
        return grid
    }
}
