package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.DJHelper
import com.dd3boh.outertune.utils.TrackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

data class BeatSample(val beatIndex: Float, val amplitude: Float)

@HiltViewModel
class TransitionEditorViewModel @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // --- Tracks ---
    private val _track1 = MutableStateFlow<Song?>(null)
    val track1: StateFlow<Song?> = _track1.asStateFlow()

    private val _track2 = MutableStateFlow<Song?>(null)
    val track2: StateFlow<Song?> = _track2.asStateFlow()

    // --- Waveform data ---
    private val _waveformData1 = MutableStateFlow(FloatArray(0))
    private val _waveformData2 = MutableStateFlow(FloatArray(0))
    val waveformData1 = _waveformData1.asStateFlow()
    val waveformData2 = _waveformData2.asStateFlow()

    private val _beatGrid1 = MutableStateFlow<List<Float>>(emptyList())
    private val _beatGrid2 = MutableStateFlow<List<Float>>(emptyList())
    val beatGrid1 = _beatGrid1.asStateFlow()
    val beatGrid2 = _beatGrid2.asStateFlow()

    // Beat-domain waveform
    private val _waveformBeatDomain1 = MutableStateFlow<List<BeatSample>>(emptyList())
    private val _waveformBeatDomain2 = MutableStateFlow<List<BeatSample>>(emptyList())
    val waveformBeatDomain1 = _waveformBeatDomain1.asStateFlow()
    val waveformBeatDomain2 = _waveformBeatDomain2.asStateFlow()

    // Derived beat indices for UI
    val beatGridIndices: StateFlow<List<Float>> = combine(_beatGrid1, _beatGrid2) { grid1, grid2 ->
        (grid1.indices.map { it.toFloat() } + grid2.indices.map { it.toFloat() }).distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Pixels per beat for rendering
    private val _pixelsPerBeatBase = MutableStateFlow(48f)
    val pixelsPerBeatBase = _pixelsPerBeatBase.asStateFlow()

    // Transition params
    private val _barsCount = MutableStateFlow(4)
    val barsCount = _barsCount.asStateFlow()
    private val _transitionDurationSeconds = MutableStateFlow(0f)
    val transitionDurationSeconds = _transitionDurationSeconds.asStateFlow()
    private val _transitionWidthFraction = MutableStateFlow(0.75f)
    val transitionWidthFraction = _transitionWidthFraction.asStateFlow()

    // Derived offset for Track 2 logic if needed
    private val _beatOffsetForTrack2 = MutableStateFlow(0f)
    val beatOffsetForTrack2 = _beatOffsetForTrack2.asStateFlow()

    // Playback state
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    private val _playbackBeat = MutableStateFlow<Float?>(null)
    val playbackBeat = _playbackBeat.asStateFlow()

    private val _playbackSpeed1 = MutableStateFlow(1f)
    val playbackSpeed1 = _playbackSpeed1.asStateFlow()

    private val _playbackSpeed2 = MutableStateFlow(1f)
    val playbackSpeed2 = _playbackSpeed2.asStateFlow()

    private var playbackTickerJob: Job? = null
    private var rampJob: Job? = null

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    // BPM Sync logic
    private var targetSpeedB = 1f
    private var initialSpeedB = 1f

    private var currentScreenWidthPx: Float = 0f

    // --- Independent Waveform Offsets (in Beats) ---
    private val _track1OffsetBeats = MutableStateFlow(0f)
    val track1OffsetBeats = _track1OffsetBeats.asStateFlow()

    private val _track2OffsetBeats = MutableStateFlow(0f)
    val track2OffsetBeats = _track2OffsetBeats.asStateFlow()

    fun setTrack1Offset(pxOffset: Float, pixelsPerBeat: Float) {
        // Invert pxOffset: Scrolling LEFT (negative px) means moving FORWARD in time (positive beats)
        _track1OffsetBeats.value = -pxOffset / pixelsPerBeat
    }

    fun setTrack2Offset(pxOffset: Float, pixelsPerBeat: Float) {
        _track2OffsetBeats.value = -pxOffset / pixelsPerBeat
    }

    private val PRE_ROLL_MS = 3000L
    private val POST_ROLL_MS = 20000L // 20 seconds post-roll

    init { viewModelScope.launch(Dispatchers.Main) { setupPlayers() } }

    private fun setupPlayers() {
        if (playerA == null) playerA = ExoPlayer.Builder(context).build().apply { volume = 1f }
        if (playerB == null) playerB = ExoPlayer.Builder(context).build().apply { volume = 1f }
    }

    fun setScreenWidth(widthPx: Float) {
        if (currentScreenWidthPx != widthPx) {
            currentScreenWidthPx = widthPx
            recalculateZoom()
        }
    }

    fun setBarsCount(count: Int) {
        _barsCount.value = count.coerceAtLeast(1)
        recalculateZoom()
    }

    private fun recalculateZoom() {
        if (currentScreenWidthPx <= 0f) return
        val zoneWidth = currentScreenWidthPx * _transitionWidthFraction.value
        val totalBeats = _barsCount.value * 4
        _pixelsPerBeatBase.value = (zoneWidth / totalBeats).coerceAtLeast(4f)
        updateTransitionDuration()
    }

    fun loadData(songAId: String, songBId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val songA = database.song(songAId).firstOrNull()
            val songB = database.song(songBId).firstOrNull()
            _track1.value = songA
            _track2.value = songB

            songA?.let {
                _waveformData1.value = loadWaveform(it.song.waveformPath, it.id)
                _beatGrid1.value = loadBeatGrid(it.song.beatGridPath, it.id)
            }
            songB?.let {
                _waveformData2.value = loadWaveform(it.song.waveformPath, it.id)
                _beatGrid2.value = loadBeatGrid(it.song.beatGridPath, it.id)
            }

            // BPM Sync Calculation
            val bpmA = calculateStableBpm(_beatGrid1.value, songA?.song?.bpm)
            val bpmB = calculateStableBpm(_beatGrid2.value, songB?.song?.bpm)

            // If tracks are within 15 BPM, sync Track B to Track A
            if (abs(bpmA - bpmB) <= 15f && bpmB > 0f) {
                targetSpeedB = 1f // Original speed
                initialSpeedB = bpmA / bpmB // Synced speed
            } else {
                targetSpeedB = 1f
                initialSpeedB = 1f
            }

            _playbackSpeed1.value = 1f
            _playbackSpeed2.value = initialSpeedB

            val pxPerBeat = 48
            val durA = songA?.song?.duration?.toFloat() ?: 1f
            val durB = songB?.song?.duration?.toFloat() ?: 1f

            _waveformBeatDomain1.value = convertWaveformToBeatDomain(_waveformData1.value, _beatGrid1.value, durA, pxPerBeat)
            _waveformBeatDomain2.value = convertWaveformToBeatDomain(_waveformData2.value, _beatGrid2.value, durB, pxPerBeat)

            updateTransitionDuration()

            withContext(Dispatchers.Main) {
                songA?.song?.localPath?.let { filePath ->
                    File(filePath).takeIf { it.exists() }?.let {
                        playerA?.setMediaItem(MediaItem.fromUri(Uri.fromFile(it)))
                        playerA?.prepare()
                    }
                }
                songB?.song?.localPath?.let { filePath ->
                    File(filePath).takeIf { it.exists() }?.let {
                        playerB?.setMediaItem(MediaItem.fromUri(Uri.fromFile(it)))
                        playerB?.prepare()
                    }
                }
            }
        }
    }

    // --- Playback / preview ---
    fun togglePlayback() {
        if (_isPlaying.value) stopPreview() else startPreview()
    }

    private fun startPreview() {
        val pA = playerA ?: return
        val pB = playerB ?: return
        val gridA = _beatGrid1.value
        val gridB = _beatGrid2.value
        if (gridA.isEmpty() || gridB.isEmpty()) return

        _isPlaying.value = true
        startPlaybackTicker()

        val beatOffsetA = _track1OffsetBeats.value
        val beatOffsetB = _track2OffsetBeats.value

        // --- Calculate Transition Alignment ---
        // The UI centers the "Transition Zone" on screen.
        // The waveforms are drawn starting from the left edge (x=0).
        // The distance from Left Edge to Green Box Start is:
        val screenWidth = currentScreenWidthPx
        val zoneWidth = screenWidth * _transitionWidthFraction.value
        val zoneStartPx = (screenWidth - zoneWidth) / 2f
        val pixelsPerBeat = _pixelsPerBeatBase.value
        val visualDelayBeats = if (pixelsPerBeat > 0) zoneStartPx / pixelsPerBeat else 0f

        // The user's scroll offset (`beatOffsetA`) defines what is at x=0.
        // The Transition Zone starts at `x = zoneStartPx`.
        // Therefore, the transition starts at `beatOffsetA + visualDelayBeats`.

        val transitionStartBeatA = beatOffsetA + visualDelayBeats
        val transitionStartBeatB = beatOffsetB + visualDelayBeats

        val preRollSec = PRE_ROLL_MS / 1000f

        // Seek relative to the user's scroll position (Left Edge) - PreRoll
        val startTimeA = (getTimestampForBeat(gridA, beatOffsetA) - preRollSec).coerceAtLeast(0f)
        val startTimeB = (getTimestampForBeat(gridB, beatOffsetB) - preRollSec).coerceAtLeast(0f)

        // For cue calculation, we need the timestamp of the Transition Start
        val transitionStartTimeA = getTimestampForBeat(gridA, transitionStartBeatA)
        val transitionStartTimeB = getTimestampForBeat(gridB, transitionStartBeatB)

        pA.seekTo((startTimeA * 1000).toLong())
        pB.seekTo((startTimeB * 1000).toLong())

        pA.volume = 1f
        pB.volume = 0f
        pA.setPlaybackSpeed(_playbackSpeed1.value)

        // Start Track B with matched BPM
        pB.setPlaybackSpeed(initialSpeedB)

        pA.play()
        pB.play()

        val startTimeSys = System.currentTimeMillis()

        // Generate RELATIVE cues (0 to totalBeats)
        val cues = DJHelper.generateDJCues(
            bars = _barsCount.value,
            fadeBeats = 4
        )

        viewModelScope.launch {
            for (cue in cues) {
                val isTrackB = cue.action.contains("B") || cue.action == "fade_in" || cue.action == "full_volume"

                // Determine absolute timestamp for this cue
                // Cue.beat is relative to Transition Start (0)
                // Absolute Cue Time = TransitionStartTime + (TimeDelta for Cue.beat)

                // Simplification: Calculate timestamps on the fly
                val absoluteCueBeat = if (isTrackB) transitionStartBeatB + cue.beat else transitionStartBeatA + cue.beat
                val cueGrid = if (isTrackB) gridB else gridA
                val cueTime = getTimestampForBeat(cueGrid, absoluteCueBeat)

                // Calculate delay: CueTime - (Track Start Time) - Elapsed
                // Track Start Time is `startTimeA` (since they started together)
                // Note: Track B technically started at startTimeB, but we started players simultaneously.
                val trackStartTime = if (isTrackB) startTimeB else startTimeA

                val elapsed = (System.currentTimeMillis() - startTimeSys) / 1000f

                // Important: Adjust for playback speed if necessary, but timestamps from grid are absolute.
                // If player is playing faster/slower, `elapsed` real time covers different amount of audio time.
                // However, ExoPlayer handles speed. 1 real second = 1 * speed audio seconds.
                // This makes precise syncing hard with `delay()`.
                // A better approach for cues is comparing `player.currentPosition`.
                // But for simplicity with existing code structure:

                // We use System time. If player is 1.05x speed, it reaches cue faster.
                val playbackSpeed = if (isTrackB) initialSpeedB else 1f
                val timeToReachCue = (cueTime - trackStartTime) / playbackSpeed

                val delayMs = ((timeToReachCue - elapsed) * 1000).toLong().coerceAtLeast(0)
                delay(delayMs)

                when (cue.action) {
                    "fade_in" -> pB.volume = cue.volume
                    "fade_out" -> pA.volume = cue.volume
                    "full_volume" -> pB.volume = 1f
                    "start" -> pA.volume = 1f
                    "end" -> { /* Transition done */ }
                }
            }

            // Wait for Post-Roll
            delay(POST_ROLL_MS)
            stopPreview()
        }

        // Start Tempo Ramp AFTER the transition window
        // Transition Duration roughly = totalBeats * (60/BPM)
        // We can just calculate the time difference between Transition End and Start
        val transitionEndBeatB = transitionStartBeatB + (_barsCount.value * 4)
        val transitionEndTimeB = getTimestampForBeat(gridB, transitionEndBeatB)
        val transitionDurationRealTime = (transitionEndTimeB - transitionStartTimeB) / initialSpeedB
        val delayBeforeRamp = ((transitionDurationRealTime) * 1000).toLong().coerceAtLeast(0)

        // Add pre-roll to the ramp delay because ramp job starts NOW
        // Ramp Delay = Time to reach Transition Start + Duration of Transition
        val timeToReachTransitionStart = (transitionStartTimeB - startTimeB) / initialSpeedB
        val totalRampDelay = ((timeToReachTransitionStart + transitionDurationRealTime) * 1000).toLong()

        rampJob = viewModelScope.launch {
            delay(totalRampDelay)
            monitorTempoRamp()
        }
    }


    private fun stopPreview() {
        _isPlaying.value = false
        playerA?.pause()
        playerB?.pause()
        // Reset speeds
        playerA?.setPlaybackSpeed(1f)
        playerB?.setPlaybackSpeed(1f)

        playbackTickerJob?.cancel()
        _playbackBeat.value = null
        rampJob?.cancel()
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = viewModelScope.launch {
            val grid = _beatGrid1.value
            while (isActive && _isPlaying.value) {
                val curSec = (playerA?.currentPosition ?: 0L) / 1000f
                _playbackBeat.value = DJHelper.timeToBeat(grid, curSec)
                delay(16)
            }
            _playbackBeat.value = null
        }
    }

    private suspend fun monitorTempoRamp() = coroutineScope {
        val pB = playerB ?: return@coroutineScope
        val rampMs = 4000L // 4 seconds to return to normal speed
        val startTime = System.currentTimeMillis()

        while (isActive && _isPlaying.value) {
            val progress = ((System.currentTimeMillis() - startTime) / rampMs.toFloat()).coerceIn(0f, 1f)
            // Interpolate from initialSpeedB to 1f
            val speed = initialSpeedB + (1f - initialSpeedB) * progress

            withContext(Dispatchers.Main) { pB.setPlaybackSpeed(speed) }
            if (progress >= 1f) break
            delay(50)
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerA?.release()
        playerB?.release()
    }

    // --- Helpers ---
    private fun updateTransitionDuration() {
        val bpm = calculateStableBpm(_beatGrid1.value, _track1.value?.song?.bpm)
        _transitionDurationSeconds.value = _barsCount.value * 4 * (60f / bpm)
    }

    private fun calculateStableBpm(grid: List<Float>, metadataBpm: Float?): Float {
        val n = 16
        if (grid.size > n) return (n * 60f) / (grid[n] - grid[0])
        return metadataBpm ?: 120f
    }

    private fun getTimestampForBeat(grid: List<Float>, beatIndex: Float): Float {
        if (grid.isEmpty()) return 0f
        val idx = beatIndex.toInt()
        if (idx in 0 until grid.size - 1) {
            val t1 = grid[idx]; val t2 = grid[idx + 1]
            val frac = beatIndex - idx
            return t1 + (t2 - t1) * frac
        }
        val sample = 4.coerceAtMost(grid.size - 1)
        val avg = (grid.last() - grid[grid.size - 1 - sample]) / sample
        return if (beatIndex < 0) grid.first() + beatIndex * avg
        else grid.last() + (beatIndex - (grid.size - 1)) * avg
    }

    private fun convertWaveformToBeatDomain(
        waveform: FloatArray, grid: List<Float>, durationSec: Float, pixelsPerBeat: Int
    ): List<BeatSample> {
        if (waveform.isEmpty() || grid.size < 2 || durationSec <= 0f) return emptyList()
        val out = ArrayList<BeatSample>((grid.size-1)*pixelsPerBeat)
        val size = waveform.size
        for (b in 0 until grid.size-1) {
            val t0 = grid[b].coerceAtLeast(0f); val t1 = grid[b+1].coerceAtMost(durationSec)
            val startIdx = ((t0/durationSec)*size).toInt().coerceIn(0,size-1)
            val endIdx = ((t1/durationSec)*size).toInt().coerceIn(0,size)
            val len = (endIdx-startIdx).coerceAtLeast(1)
            for (px in 0 until pixelsPerBeat) {
                val s = startIdx + (px.toFloat()/pixelsPerBeat*len).toInt()
                val e = startIdx + ((px+1).toFloat()/pixelsPerBeat*len).toInt()
                var maxAmp = 0f
                for (i in s until e.coerceAtMost(size)) maxAmp = maxOf(maxAmp, abs(waveform[i]))
                out.add(BeatSample(b + px.toFloat()/pixelsPerBeat, maxAmp))
            }
        }
        return out
    }

    private fun loadWaveform(path: String?, id: String) = File(path ?: "${context.cacheDir}/analysis_data/${id}_waveform.dat")
        .takeIf { it.exists() }?.readText()?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: FloatArray(0)

    private fun loadBeatGrid(path: String?, id: String) = File(path ?: "${context.cacheDir}/analysis_data/${id}_beats.dat")
        .takeIf { it.exists() }?.readText()?.split(",")?.mapNotNull { it.toFloatOrNull() }?.map { it / 1000f } ?: emptyList()
}