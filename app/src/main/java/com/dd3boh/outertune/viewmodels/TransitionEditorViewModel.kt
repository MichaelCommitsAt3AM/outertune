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

    // --- Waveform data (Beat Domain) ---
    private val _waveformBeatDomain1 = MutableStateFlow<List<BeatSample>>(emptyList())
    private val _waveformBeatDomain2 = MutableStateFlow<List<BeatSample>>(emptyList())
    val waveformBeatDomain1 = _waveformBeatDomain1.asStateFlow()
    val waveformBeatDomain2 = _waveformBeatDomain2.asStateFlow()

    // Derived beat indices for UI markers (Pure Integer Grid 0.0, 1.0, 2.0...)
    val beatMarkers: StateFlow<List<Float>> =
        waveformBeatDomain1.map { samples ->
            if (samples.isEmpty()) emptyList()
            else {
                val maxBeat = samples.last().beatIndex
                (0..maxBeat.toInt()).map { it.toFloat() }
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    // Pixels per beat (Zoom level)
    private val _pixelsPerBeatBase = MutableStateFlow(48f)
    val pixelsPerBeatBase = _pixelsPerBeatBase.asStateFlow()

    // --- Transition State ---
    private val _barsCount = MutableStateFlow(4)
    val barsCount = _barsCount.asStateFlow()

    private val _transitionDurationSeconds = MutableStateFlow(0f)
    val transitionDurationSeconds = _transitionDurationSeconds.asStateFlow()

    private val _transitionWidthFraction = MutableStateFlow(0.75f)
    val transitionWidthFraction = _transitionWidthFraction.asStateFlow()

    // Modes
    private val _overlapMode = MutableStateFlow("Overlap")
    val overlapMode = _overlapMode.asStateFlow()

    private val _eqMode = MutableStateFlow("None")
    val eqMode = _eqMode.asStateFlow()

    private val _effectMode = MutableStateFlow("None")
    val effectMode = _effectMode.asStateFlow()

    // --- Playback State ---
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackBeatMarker = MutableStateFlow<Float?>(null)
    val playbackBeatMarker = _playbackBeatMarker.asStateFlow()

    // Independent Waveform Offsets
    private val _track1OffsetBeats = MutableStateFlow(0f)
    val track1OffsetBeats = _track1OffsetBeats.asStateFlow()

    private val _track2OffsetBeats = MutableStateFlow(0f)
    val track2OffsetBeats = _track2OffsetBeats.asStateFlow()

    private var playbackJob: Job? = null
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    // Internal State for Playback Sync
    private var initialSpeedB = 1f
    private var currentScreenWidthPx: Float = 0f

    // Cache raw refined grids to perform time lookups during playback
    private var rawGrid1: List<Float> = emptyList()
    private var rawGrid2: List<Float> = emptyList()

    private val PRE_ROLL_MS = 3000L

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

            // 1. Load Raw Data
            var pcmA = FloatArray(0)
            var pcmB = FloatArray(0)
            var gridA = emptyList<Float>()
            var gridB = emptyList<Float>()

            songA?.let {
                pcmA = loadWaveform(it.song.waveformPath, it.id)
                gridA = loadBeatGrid(it.song.beatGridPath, it.id)
            }
            songB?.let {
                pcmB = loadWaveform(it.song.waveformPath, it.id)
                gridB = loadBeatGrid(it.song.beatGridPath, it.id)
            }

            // Store for playback
            rawGrid1 = gridA
            rawGrid2 = gridB

            // 2. Load EXACT duration from metadata file to prevent drift
            val durA = songA?.let { loadExactDuration(it.id) }
                ?: songA?.song?.duration?.toFloat() ?: 1f

            val durB = songB?.let { loadExactDuration(it.id) }
                ?: songB?.song?.duration?.toFloat() ?: 1f

            val pxPerBeat = 64

            // 3. Calculate speeds
            val bpmA = songA?.song?.bpm ?: 120f
            val bpmB = songB?.song?.bpm ?: 120f
            initialSpeedB = if (bpmB > 0) bpmA / bpmB else 1f

            // 4. Generate Beat-Domain Waveforms (Elastic Audio)
            // We use 64 samples per beat to allow high-res drawing even when zoomed in
            val samplesPerBeat = 64

            _waveformBeatDomain1.value = convertWaveformToBeatDomain(pcmA, gridA, durA, samplesPerBeat)
            _waveformBeatDomain2.value = convertWaveformToBeatDomain(pcmB, gridB, durB, samplesPerBeat)

            updateTransitionDuration()

            // Prepare Players
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

    // --- Playback Logic ---
    fun togglePlayback() {
        if (_isPlaying.value) stopPreview() else startPreview()
    }

    private fun startPreview() {
        val pA = playerA ?: return
        val pB = playerB ?: return
        val gridA = rawGrid1
        val gridB = rawGrid2
        if (gridA.isEmpty() || gridB.isEmpty()) return

        _isPlaying.value = true

        val beatOffsetA = _track1OffsetBeats.value
        val beatOffsetB = _track2OffsetBeats.value

        val zoneFraction = _transitionWidthFraction.value
        val marginFraction = (1f - zoneFraction) / 2f
        val beatsInZone = _barsCount.value * 4f
        val visualDelayBeats = if (zoneFraction > 0f) (marginFraction / zoneFraction) * beatsInZone else 0f

        // The "Anchor" is the start of the Green Zone
        val anchorBeatA = beatOffsetA + visualDelayBeats
        val anchorBeatB = beatOffsetB + visualDelayBeats

        // 1. Calculate Exact Timestamp of the Anchor
        val timeAnchorA = getTimestampForBeat(gridA, anchorBeatA)
        val timeAnchorB = getTimestampForBeat(gridB, anchorBeatB)

        // 2. Set Speeds
        val speedA = 1f
        val speedB = initialSpeedB
        pA.setPlaybackSpeed(speedA)
        pB.setPlaybackSpeed(speedB)

        // 3. SEEK EARLIER (The "Stabilization" Trick)
        // Instead of seeking to -3s, we seek to -3.5s but keep volume silent
        // This gives the audio engine 500ms to "warm up" and lock phase before we hear it.
        val STABILIZATION_MS = 500L
        val prerollSeconds = (PRE_ROLL_MS + STABILIZATION_MS) / 1000f

        // Calculate seek positions relative to the Anchor
        val seekA = (timeAnchorA - prerollSeconds).coerceAtLeast(0f)
        val seekB = (timeAnchorB - (prerollSeconds * speedB)).coerceAtLeast(0f)

        pA.seekTo((seekA * 1000).toLong())
        pB.seekTo((seekB * 1000).toLong())

        // 4. Start SILENTLY
        pA.volume = 0f
        pB.volume = 0f

        pA.play()
        pB.play()

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val entryBeatA = anchorBeatA
            val endBeatA = anchorBeatA + beatsInZone

            // Convert Pre-roll duration to Beats (approx)
            // We want to unmute Track A exactly 'PRE_ROLL_MS' before the anchor
            // This effectively hides the "Stabilization" startup glitch
            val bpmA = _track1.value?.song?.bpm ?: 120f
            val beatsPerSec = bpmA / 60f
            val preRollBeats = (PRE_ROLL_MS / 1000f) * beatsPerSec

            val unmuteBeatA = anchorBeatA - preRollBeats

            var aUnmuted = false
            var bUnmuted = false
            var aMuted = false

            while (isActive && _isPlaying.value) {
                // Monitor position based on Track A (Master)
                val posA = pA.currentPosition / 1000f
                val currentBeatA = DJHelper.timeToBeat(gridA, posA)

                _playbackBeatMarker.value = currentBeatA

                // --- 1. UNMUTE MASTER (Track A) ---
                // Wait for the "Stabilization" phase to pass, then unmute A
                if (!aUnmuted && currentBeatA >= unmuteBeatA) {
                    pA.volume = 1f
                    aUnmuted = true
                }

                // --- 2. UNMUTE INCOMING (Track B) ---
                // Unmute exactly when we cross the start line
                if (!bUnmuted && currentBeatA >= entryBeatA) {
                    pB.volume = 1f
                    bUnmuted = true

                    // OPTIONAL: One-shot sync correction
                    // If Track B started slightly late (common), nudge it once here.
                    val posB = pB.currentPosition / 1000f
                    val currentBeatB = DJHelper.timeToBeat(gridB, posB)
                    val expectedBeatB = entryBeatA + (currentBeatA - entryBeatA) // should match A

                    // If B is behind A by > 10ms, seek it forward instantly
                    // (Only do this if the gap is noticeable but not huge)
                    val offset = currentBeatB - (anchorBeatB + (currentBeatA - anchorBeatA))
                    if (kotlin.math.abs(offset) > 0.05f) { // ~25ms at 120bpm
                        // Log.d("SYNC", "One-shot correction triggered")
                        // You could seek here, but usually, the "Stabilization" fix above prevents this.
                    }
                }

                // --- 3. MUTE OUTGOING (Track A) ---
                if (!aMuted && currentBeatA >= endBeatA) {
                    pA.volume = 0f
                    aMuted = true
                }

                delay(20)
            }
        }
    }

    private fun stopPreview() {
        _isPlaying.value = false
        playerA?.pause()
        playerB?.pause()
        playerA?.setPlaybackSpeed(1f)
        playerB?.setPlaybackSpeed(1f)
        playerA?.volume = 1f
        playerB?.volume = 1f
        playbackJob?.cancel()
        _playbackBeatMarker.value = null
    }

    /**
     * SNAPS BTrack timestamps to the nearest loud amplitude peak.
     * This ensures the visual marker lines up exactly with the visual spike.
     */
    private fun refineBeatGrid(
        roughGrid: List<Float>,
        waveform: FloatArray,
        durationSec: Float
    ): List<Float> {
        if (waveform.isEmpty() || roughGrid.isEmpty() || durationSec <= 0) return roughGrid

        val sampleRate = waveform.size / durationSec
        // Search window: +/- 50ms around the detected timestamp
        val windowSizeMs = 50
        val windowSamples = (windowSizeMs / 1000f * sampleRate).toInt()

        return roughGrid.map { timestamp ->
            val centerIndex = (timestamp * sampleRate).toInt()
            val start = (centerIndex - windowSamples).coerceAtLeast(0)
            val end = (centerIndex + windowSamples).coerceAtMost(waveform.size - 1)

            var maxIndex = centerIndex
            var maxAmp = -1f

            for (i in start..end) {
                val amp = abs(waveform[i])
                if (amp > maxAmp) {
                    maxAmp = amp
                    maxIndex = i
                }
            }
            (maxIndex / sampleRate)
        }
    }

    /**
     * Converts Time-Domain Audio -> Beat-Domain Visualization.
     * This "stretches" the audio between beat[i] and beat[i+1] to fill exactly 1.0 beat unit.
     */
    private fun convertWaveformToBeatDomain(
        waveform: FloatArray,
        grid: List<Float>,
        durationSec: Float,
        samplesPerBeat: Int
    ): List<BeatSample> {
        if (waveform.isEmpty() || grid.size < 2 || durationSec <= 0f) return emptyList()
        val out = ArrayList<BeatSample>()
        val size = waveform.size

        for (beatIndex in 0 until grid.size - 1) {
            val tStart = grid[beatIndex].coerceAtLeast(0f)
            val tEnd = grid[beatIndex + 1].coerceAtMost(durationSec)

            val startIdx = ((tStart / durationSec) * size).toInt().coerceIn(0, size - 1)
            val endIdx = ((tEnd / durationSec) * size).toInt().coerceIn(0, size)
            val chunkLen = (endIdx - startIdx).coerceAtLeast(1)

            for (i in 0 until samplesPerBeat) {
                val beatFraction = i.toFloat() / samplesPerBeat

                val audioStart = startIdx + (beatFraction * chunkLen).toInt()
                val audioEnd = startIdx + ((beatFraction + (1f/samplesPerBeat)) * chunkLen).toInt().coerceAtMost(size)

                var maxAmp = 0f
                for (k in audioStart until audioEnd) {
                    if (k < size) maxAmp = maxOf(maxAmp, abs(waveform[k]))
                }

                val finalBeatIndex = beatIndex.toFloat() + beatFraction
                out.add(BeatSample(finalBeatIndex, maxAmp))
            }
        }
        return out
    }

    private fun updateTransitionDuration() {
        val bpm = _track1.value?.song?.bpm ?: 120f
        _transitionDurationSeconds.value = _barsCount.value * 4 * (60f / bpm)
    }

    private fun getTimestampForBeat(grid: List<Float>, beatIndex: Float): Float {
        if (grid.isEmpty()) return 0f
        val lastIdx = grid.size - 1
        if (beatIndex < 0) {
            val avgStep = if (grid.size > 1) (grid[1] - grid[0]) else 0.5f
            return (grid[0] + beatIndex * avgStep).coerceAtLeast(0f)
        }
        if (beatIndex > lastIdx) {
            val avgStep = if (grid.size > 1) (grid[lastIdx] - grid[lastIdx - 1]) else 0.5f
            return grid[lastIdx] + (beatIndex - lastIdx) * avgStep
        }
        val idx = beatIndex.toInt()
        val t1 = grid[idx]
        val t2 = if (idx + 1 < grid.size) grid[idx + 1] else t1 + 0.5f
        return t1 + (t2 - t1) * (beatIndex - idx)
    }

    private fun loadExactDuration(id: String): Float? {
        val file = File(context.cacheDir, "analysis_data/${id}_metadata.dat")
        return if (file.exists()) file.readText().toFloatOrNull() else null
    }

    private fun loadWaveform(path: String?, id: String) = File(path ?: "${context.cacheDir}/analysis_data/${id}_waveform.dat")
        .takeIf { it.exists() }?.readText()?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: FloatArray(0)

    private fun loadBeatGrid(path: String?, id: String): List<Float> {
        return File(path ?: "${context.cacheDir}/analysis_data/${id}_beats.dat")
            .takeIf { it.exists() }
            ?.readText()
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.map { it / 1000f }
            ?: emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        playerA?.release()
        playerB?.release()
    }
}