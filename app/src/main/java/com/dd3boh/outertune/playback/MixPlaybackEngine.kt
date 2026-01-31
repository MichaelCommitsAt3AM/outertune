package com.dd3boh.outertune.playback

import android.util.Log
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

    // Mix State
    private var currentTransitionCache: TransitionEntity? = null
    private var currentTransitionPlan: TransitionPlan? = null
    private var currentTransitionConfig: TransitionConfig? = null
    private var isTransitionDataLoaded = false
    private var isPllTriggered = false
    private var nextSongnormFactorCache: Float? = null
    private var lastLogTime = 0L

    // Initialize logical state locally
    private var localLogicalState = LogicalPlayerState()

    init {
        // Listen to Deck changes
        // The deckManager callback we passed in constructor of MusicService is now tricky...
        // We will likely refactor DeckManager to take a listener or expose a flow.
        // For now, let's assume DeckManager still calls the callback we gave it.
        // Wait, DeckManager is injected? No, constructed in Service.
        // We will pass the DeckManager instance here.
    }

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

        // 1. Clamp to Logical Duration
        val clampedPosition = if (logicalDuration > 0) {
            positionMs.coerceIn(0L, logicalDuration)
        } else {
            positionMs
        }

        if (deckManager.activeDeck.isPlaying && state.isTransitionActive) {
            // Cancel active crossfade if seeking
            deckManager.cancelCrossfade()
        }

        activePlayer.value.seekTo(clampedPosition)

        // Optimistic update
        localLogicalState = state.copy(currentPositionMs = clampedPosition)
        updateLogicalStateCallback(localLogicalState)
    }

    fun onActiveDeckChanged(newPlayer: ExoPlayer) {
        Log.i(TAG, "Active Deck Changed to: ${if (newPlayer == deckManager.playerA) "A" else "B"}")
        _activePlayer.value = newPlayer
        
        // Advance Queue
        val currentQ = queueBoard.getCurrentQueue()
        val nextIndex = if (currentQ != null) currentQ.queuePos + 1 else -1

        if (currentQ != null) {
            Log.d(TAG, "Advancing queue from index ${currentQ.queuePos} to $nextIndex")
            queueBoard.setCurrQueuePosIndex(nextIndex)
        }
        
        // Prepare next song (Pre-warm)
        refreshTransition(newPlayer.currentMediaItem?.mediaId)

        // Reset logical state for transition
         localLogicalState = localLogicalState.copy(isTransitionActive = false)
         updateLogicalStateCallback(localLogicalState)
    }
    
    // --- Logic Moved from MusicService ---

    private fun startMixPoller() {
        pollerJob = scope.launch {
            lastLogTime = 0L
            while (isActive) {
                if (activePlayer.value.isPlaying) { // activeDeck is playing
                     withContext(Dispatchers.Main) { // UI updates on Main
                        updateLogicalState() // Update UI Clock
                        checkMixStatus()     // Check Triggers
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

    private suspend fun checkMixStatus() {
        val now = System.currentTimeMillis()
        val player = activePlayer.value
        val currentPosition = player.currentPosition
        val transition = currentTransitionCache

        // Log every 3s
        if (now - lastLogTime > 3000) {
             // ... logging logic ...
             lastLogTime = now
        }
        
        // Adaptive Polling Logic
        val timeToExit = if (transition != null) transition.exitPointMs - currentPosition else Long.MAX_VALUE
        val isCloseToTransition = timeToExit < 10000 // 10 seconds
        val interval = if (isCloseToTransition) 20 else 500

        if (now - lastMixCheckTime < interval) return
        lastMixCheckTime = now

        if (localLogicalState.isTransitionActive) return
        if (!player.isPlaying) return
        if (transition == null) return

        // Integrity Checks
        val realDuration = player.duration
        if (realDuration > 0 && transition.exitPointMs > (realDuration - 500)) return

        val nextSong = queueBoard.peekNext()
        if (nextSong == null || nextSong.id != transition.toSongId) {
            loadTransitionOnce(player.currentMediaItem?.mediaId ?: "", nextSong?.id)
            return
        }
        // Repeat One check...
        
        // Lazy Load (15s before)
        if (!isTransitionDataLoaded && !isPllTriggered && transition.exitPointMs > 5000) {
            val loadTrigger = transition.exitPointMs - 15000
            if (currentPosition >= loadTrigger) {
                performLazyLoading(transition, nextSong.id)
            }
        }

        // PLL Trigger (3s before Exit)
        val triggerTime = transition.exitPointMs - 3000
        if (currentPosition >= triggerTime && !isPllTriggered) {
             val plan = currentTransitionPlan
             val config = currentTransitionConfig
             if (plan != null && config != null) {
                 isPllTriggered = true
                 deckManager.startPllTransition(plan, transition.durationMs, config)
             }
        }

        // Logical Switch (Exit Point)
        if (currentPosition >= transition.exitPointMs) {
             if (localLogicalState.activeMetadata?.id == nextSong.id) return
             
             // Trigger Logical Switch
             offloadScopeLaunch {
                 val nextIndex = player.currentMediaItemIndex + 1
                 val songAfterNext = queueBoard.getSongAtIndex(nextIndex + 1)
                 loadTransitionOnce(nextSong.id, songAfterNext?.id)
                 
                 withContext(Dispatchers.Main) {
                     localLogicalState = LogicalPlayerState(
                        activeMetadata = nextSong,
                        currentPositionMs = 0L,
                        durationMs = currentTransitionCache?.exitPointMs ?: (nextSong.duration * 1000L),
                        isTransitionActive = true
                     )
                     isPllTriggered = false
                     updateLogicalStateCallback(localLogicalState)
                 }
             }
        }
    }
    
    // Helper to launch in scope (since we are suspend)
    private fun offloadScopeLaunch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
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

                 val bpmA = songA.displayBpm ?: 0f
                 val bpmB = songB.displayBpm ?: 0f

                 if (gridA != null && gridB != null && bpmA > 0 && bpmB > 0) {
                     currentTransitionConfig = TransitionConfig(
                         overlapMode = transition.overlapMode,
                         eqMode = transition.eqMode,
                         effectMode = transition.effectMode,
                         barsCount = (transition.durationBeats ?: 32) / 4
                     )
                     
                     // Re-calc anchors based on PARTIAL grid
                     val exitBeatA = TransitionMath.getBeatForTimestamp(gridA, exitSec)
                     val entryBeatB = TransitionMath.getBeatForTimestamp(gridB, entrySec)

                     currentTransitionPlan = TransitionPlan(
                         initialSpeedB = (if (transition.syncTempo && bpmB > 0) bpmA / bpmB else 1f).toDouble(),
                         gridScalarB = 1.0,
                         anchorBeatA = exitBeatA,
                         anchorBeatB = entryBeatB,
                         transitionDurationBeats = (durSec * (bpmA/60.0)).toDouble(), 
                         exitPointMs = transition.exitPointMs,
                         entryPointMs = transition.entryPointMs,
                         durationMs = transition.durationMs,
                         gridA = gridA,
                         gridB = gridB
                     )
                     isTransitionDataLoaded = true
                     Log.d(TAG, "Lazy loading complete. Plan ready.")
                 } else {
                     Log.w(TAG, "Lazy loading failed (missing grids or BPM).")
                     // Don't try again repeatedly
                     isTransitionDataLoaded = true 
                 }
            }
        }
    }

    // Public method called by Service when track changes
    fun refreshTransition(currentId: String?) {
        if (currentId == null) return
        
        // Apply cached norm
        if (nextSongnormFactorCache != null && activePlayer.value.currentMediaItem?.mediaId == currentId) {
             // Request volume normalization update from service? 
             // Or handle volume here? MusicService handles volume.
             // We might need a callback or shared flow for normalization.
        }

        scope.launch {
            val nextSong = queueBoard.peekNext()
            loadTransitionOnce(currentId, nextSong?.id)
        }
    }

    private fun loadTransitionOnce(currentId: String, nextId: String?) {
        loadJob?.cancel()
        currentTransitionCache = null
        currentTransitionPlan = null
        currentTransitionConfig = null
        isTransitionDataLoaded = false
        
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
                         
                         // Pre-fetch normalization
                         val nextFormat = database.format(nextId).firstOrNull()
                         val nextNorm = if (nextFormat?.loudnessDb != null) {
                             min(10f.pow(-nextFormat.loudnessDb.toFloat() / 20), 1f)
                         } else 1f
                         nextSongnormFactorCache = nextNorm
     
                         val mediaItem = nextSong.toMediaItem()
                         
                         // Approximate start time (Entry Point - 3s) since we don't have accurate grid yet
                         val startMs = (transition.entryPointMs - 3000).coerceAtLeast(0)
     
                         withContext(Dispatchers.Main) {
                             if (!deckManager.standbyDeck.isPlaying) {
                                 deckManager.prepareNext(mediaItem, startMs, speedRatio)
                                 deckManager.setStandbyVolumeMultiplier(nextNorm)
                             }
                         }
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
