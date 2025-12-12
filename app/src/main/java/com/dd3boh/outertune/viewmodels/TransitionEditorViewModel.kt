package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.DJHelper
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

    // Derived beat indices
    val beatGridIndices: StateFlow<List<Float>> = combine(_beatGrid1, _beatGrid2) { grid1, grid2 ->
        (grid1.indices.map { it.toFloat() } + grid2.indices.map { it.toFloat() }).distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Pixels per beat
    private val _pixelsPerBeatBase = MutableStateFlow(48f)
    val pixelsPerBeatBase = _pixelsPerBeatBase.asStateFlow()

    // --- Transition State ---
    private val _barsCount = MutableStateFlow(4)
    val barsCount = _barsCount.asStateFlow()

    private val _transitionDurationSeconds = MutableStateFlow(0f)
    val transitionDurationSeconds = _transitionDurationSeconds.asStateFlow()

    private val _transitionWidthFraction = MutableStateFlow(0.75f)
    val transitionWidthFraction = _transitionWidthFraction.asStateFlow()

    // Modes (Moved from UI to VM so logic can access them)
    private val _overlapMode = MutableStateFlow("Overlap")
    val overlapMode = _overlapMode.asStateFlow()

    private val _eqMode = MutableStateFlow("None")
    val eqMode = _eqMode.asStateFlow()

    private val _effectMode = MutableStateFlow("None")
    val effectMode = _effectMode.asStateFlow()

    // --- Playback State ---
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackBeat = MutableStateFlow<Float?>(null)
    val playbackBeat = _playbackBeat.asStateFlow()

    private val _playbackSpeed1 = MutableStateFlow(1f)
    val playbackSpeed1 = _playbackSpeed1.asStateFlow()
    private val _playbackSpeed2 = MutableStateFlow(1f)
    val playbackSpeed2 = _playbackSpeed2.asStateFlow()

    private var playbackJob: Job? = null
    private var rampJob: Job? = null

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    private var initialSpeedB = 1f
    private var currentScreenWidthPx: Float = 0f

    // --- Independent Waveform Offsets ---
    private val _track1OffsetBeats = MutableStateFlow(0f)
    val track1OffsetBeats = _track1OffsetBeats.asStateFlow()

    private val _track2OffsetBeats = MutableStateFlow(0f)
    val track2OffsetBeats = _track2OffsetBeats.asStateFlow()

    private val PRE_ROLL_MS = 3000L
    private val POST_ROLL_MS = 10000L

    init { viewModelScope.launch(Dispatchers.Main) { setupPlayers() } }

    private fun setupPlayers() {
        if (playerA == null) playerA = ExoPlayer.Builder(context).build().apply { volume = 1f }
        if (playerB == null) playerB = ExoPlayer.Builder(context).build().apply { volume = 1f }
    }

    // --- Setters ---
    fun setOverlapMode(mode: String) { _overlapMode.value = mode }
    fun setEqMode(mode: String) { _eqMode.value = mode }
    fun setEffectMode(mode: String) { _effectMode.value = mode }

    fun setTrack1Offset(pxOffset: Float, pixelsPerBeat: Float) {
        if (pixelsPerBeat > 0) _track1OffsetBeats.value = -pxOffset / pixelsPerBeat
    }
    fun setTrack2Offset(pxOffset: Float, pixelsPerBeat: Float) {
        if (pixelsPerBeat > 0) _track2OffsetBeats.value = -pxOffset / pixelsPerBeat
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

    // --- Data Loading ---
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

            val bpmA = calculateStableBpm(_beatGrid1.value, songA?.song?.bpm)
            val bpmB = calculateStableBpm(_beatGrid2.value, songB?.song?.bpm)


            if (abs(bpmA - bpmB) <= 15f && bpmB > 0f) {
                initialSpeedB = bpmA / bpmB
            } else {
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

    // --- Playback / Preview Logic ---
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

        val beatOffsetA = _track1OffsetBeats.value
        val beatOffsetB = _track2OffsetBeats.value

        // -----------------------------------------------------------------------
        // 1. GEOMETRY & ANCHOR POINTS
        // -----------------------------------------------------------------------
        val zoneFraction = _transitionWidthFraction.value
        val marginFraction = (1f - zoneFraction) / 2f
        val totalBeatsInZone = _barsCount.value * 4f
        val visualDelayBeats = if (zoneFraction > 0) (marginFraction / zoneFraction) * totalBeatsInZone else 0f

        val anchorBeatA = beatOffsetA + visualDelayBeats
        val anchorBeatB = beatOffsetB + visualDelayBeats

        // -----------------------------------------------------------------------
        // 2. CALCULATE PLAYBACK SPEEDS (BEATMATCH)
        // -----------------------------------------------------------------------
        val masterSpeed = _playbackSpeed1.value

        // Fetch actual BPMs
        val bpmA = _track1.value?.song?.bpm ?: 120f
        val bpmB = _track2.value?.song?.bpm ?: 120f

        // Beatmatch: Track B speed = bpmA / bpmB
        val syncSpeedB = (bpmA / bpmB) * masterSpeed

        // Logging
        Log.d("DEBUG_BPM", "Track A playback speed: $masterSpeed")
        Log.d("DEBUG_BPM", "Track B original BPM: $bpmB, Track B sync speed (beatmatched): $syncSpeedB")
        Log.d("DEBUG_BPM", "Track A BPM: $bpmA, Track B adjusted BPM: ${bpmB * syncSpeedB}")

        // -----------------------------------------------------------------------
        // 3. CALCULATE SEEK POSITIONS
        // -----------------------------------------------------------------------
        val timeStartA = getTimestampForBeat(gridA, anchorBeatA)
        val timeStartB = getTimestampForBeat(gridB, anchorBeatB)
        val timeAtLeftEdgeA = getTimestampForBeat(gridA, beatOffsetA)

        val audioDurationToAnchorA = timeStartA - timeAtLeftEdgeA
        val realSecondsToAnchor = audioDurationToAnchorA / masterSpeed

        val rawSeekA = timeStartA - ((realSecondsToAnchor + (PRE_ROLL_MS / 1000f)) * masterSpeed)
        val rawSeekB = timeStartB - ((realSecondsToAnchor + (PRE_ROLL_MS / 1000f)) * syncSpeedB)

        val overflowA = if (rawSeekA < 0) -rawSeekA / masterSpeed else 0f
        val overflowB = if (rawSeekB < 0) -rawSeekB / syncSpeedB else 0f
        val reducePreRollBy = maxOf(overflowA, overflowB)
        val actualPreRollSec = ((PRE_ROLL_MS / 1000f) - reducePreRollBy).coerceAtLeast(0f)
        val totalRealTime = realSecondsToAnchor + actualPreRollSec

        val finalSeekA = (timeStartA - (totalRealTime * masterSpeed)).coerceAtLeast(0f)
        val finalSeekB = (timeStartB - (totalRealTime * syncSpeedB)).coerceAtLeast(0f)

        // -----------------------------------------------------------------------
        // 4. PLAYER SETUP
        // -----------------------------------------------------------------------
        pA.seekTo((finalSeekA * 1000).toLong())
        pB.seekTo((finalSeekB * 1000).toLong())

        pA.volume = 1f
        pB.volume = 0f

        pA.setPlaybackSpeed(masterSpeed)
        pB.setPlaybackSpeed(syncSpeedB)

        pA.play()
        pB.play()

        // log positions every 500ms to verify beat alignment
        viewModelScope.launch {
            while (_isPlaying.value) {
                val beatA = DJHelper.timeToBeat(gridA, pA.currentPosition / 1000f)
                val beatB = DJHelper.timeToBeat(gridB, pB.currentPosition / 1000f)

                Log.d(
                    "DEBUG_BPM",
                    "Positions - TrackA: ${pA.currentPosition} ms (beat $beatA), " +
                            "TrackB: ${pB.currentPosition} ms (beat $beatB)"
                )

                delay(500)
            }
        }

        // -----------------------------------------------------------------------
        // 5. CUE GENERATION
        // -----------------------------------------------------------------------
        val rawCues = DJHelper.generateDJCues(
            bars = _barsCount.value,
            mode = _overlapMode.value
        )

        val executionPlan = rawCues.map { cue ->
            val isTrackB = cue.track == "B"
            val grid = if (isTrackB) gridB else gridA
            val startBeat = if (isTrackB) anchorBeatB else anchorBeatA
            val absBeat = startBeat + cue.beat
            val absTime = getTimestampForBeat(grid, absBeat)
            Triple(absTime, cue, isTrackB)
        }.sortedBy { it.first }

        val stopTime = getTimestampForBeat(gridA, anchorBeatA + totalBeatsInZone) + (POST_ROLL_MS / 1000f)

        // -----------------------------------------------------------------------
        // 6. MONITORING LOOP
        // -----------------------------------------------------------------------
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val executed = BooleanArray(executionPlan.size) { false }

            while (isActive && _isPlaying.value) {
                val posA = pA.currentPosition / 1000f
                val posB = pB.currentPosition / 1000f

                if (posA > 0) _playbackBeat.value = DJHelper.timeToBeat(gridA, posA)

                executionPlan.forEachIndexed { i, (triggerTime, cue, isTrackB) ->
                    if (!executed[i]) {
                        val currentPos = if (isTrackB) posB else posA
                        if (currentPos >= triggerTime) {
                            executed[i] = true
                            withContext(Dispatchers.Main) {
                                val player = if (cue.track == "A") pA else pB
                                player.volume = cue.volume
                            }
                        }
                    }
                }

                if (posA >= stopTime) stopPreview()
                delay(16)
            }
        }
    }



    private fun stopPreview() {
        _isPlaying.value = false
        playerA?.pause()
        playerB?.pause()
        playerA?.setPlaybackSpeed(1f)
        playerB?.setPlaybackSpeed(1f)

        // Reset volumes for safety
        playerA?.volume = 1f
        playerB?.volume = 1f

        playbackJob?.cancel()
        playbackJob = null
        _playbackBeat.value = null
        rampJob?.cancel()
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
        if (grid.size < 2) return metadataBpm ?: 120f
        val intervals = grid.zipWithNext { a, b -> b - a }
        val avgInterval = intervals.average().toFloat()
        return if (avgInterval > 0) 60f / avgInterval else metadataBpm ?: 120f
    }

    private fun getTimestampForBeat(grid: List<Float>, beatIndex: Float): Float {
        if (grid.isEmpty()) return 0f

        // Robust interpolation/extrapolation
        val lastIdx = grid.size - 1
        if (beatIndex < 0) {
            val avgStep = if (grid.size > 1) (grid[1] - grid[0]) else 0.5f
            return (grid[0] + beatIndex * avgStep).coerceAtLeast(0f)
        }
        if (beatIndex > lastIdx) {
            val avgStep = if (grid.size > 5) (grid[lastIdx] - grid[lastIdx - 4]) / 4f
            else if (grid.size > 1) grid[lastIdx] - grid[lastIdx - 1]
            else 0.5f
            return grid[lastIdx] + (beatIndex - lastIdx) * avgStep
        }

        val idx = beatIndex.toInt()
        val t1 = grid[idx]; val t2 = grid[idx + 1]
        return t1 + (t2 - t1) * (beatIndex - idx)
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