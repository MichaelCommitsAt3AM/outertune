package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.ui.component.BeatGridMarker
import com.dd3boh.outertune.utils.DJHelper
import com.dd3boh.outertune.utils.TransitionMixer
import com.dd3boh.outertune.utils.analysis.BeatGridNormalizer
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

    // Audio FX
    private var eqA: Equalizer? = null
    private var eqB: Equalizer? = null

    // Derived beat indices for UI markers — keep this driven by waveform domain so UI matches what is drawn
    val beatMarkers: StateFlow<List<BeatGridMarker>> =
        waveformBeatDomain1.map { samples ->
            if (samples.isEmpty()) emptyList()
            else {
                val maxBeat = samples.last().beatIndex
                (0..maxBeat.toInt()).map { index ->
                    BeatGridMarker(
                        beatIndex = index.toFloat(),
                        isDownbeat = index % 4 == 0,
                        isGhost = false
                    )
                }
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
    private var initialSpeedB = 1.0 // Changed to Double
    private var currentScreenWidthPx: Float = 0f

    private var trackBGridScalar = 1.0 // Changed to Double

    // Cache raw refined grids to perform time lookups during playback
    // Using Double for high precision timing
    private var rawGrid1: List<Double> = emptyList()
    private var rawGrid2: List<Double> = emptyList()

    private val PRE_ROLL_MS = 3000L

    init { viewModelScope.launch(Dispatchers.Main) { setupPlayers() } }

    private fun setupPlayers() {
        if (playerA == null) {
            playerA = ExoPlayer.Builder(context).build().apply { volume = 1f }
        }
        if (playerB == null) {
            playerB = ExoPlayer.Builder(context).build().apply { volume = 1f }
        }
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

            var pcmA = FloatArray(0)
            var pcmB = FloatArray(0)
            var visualGridA = emptyList<Float>()
            var visualGridB = emptyList<Float>()
            var syncGridA = emptyList<Double>()
            var syncGridB = emptyList<Double>()

            songA?.let {
                pcmA = loadWaveform(it.song.waveformPath, it.id)
                visualGridA = loadBeatGridFloat(null, it.id, suffix = "_visual")
                syncGridA = loadBeatGridDouble(it.song.beatGridPath, it.id)
            }
            songB?.let {
                pcmB = loadWaveform(it.song.waveformPath, it.id)
                visualGridB = loadBeatGridFloat(null, it.id, suffix = "_visual")
                syncGridB = loadBeatGridDouble(it.song.beatGridPath, it.id)
            }

            // Determine analysis and display BPM (displayBpm field if present, else fallback)
            val analysisBpmA = songA?.song?.bpm ?: 120f
            val analysisBpmB = songB?.song?.bpm ?: 120f
            val displayBpmA = songA?.song?.displayBpm ?: analysisBpmA
            val displayBpmB = songB?.song?.displayBpm ?: analysisBpmB

            // durations (use exact if available)
            val durA = songA?.let { loadExactDuration(it.id) } ?: songA?.song?.duration?.toFloat() ?: 1f
            val durB = songB?.let { loadExactDuration(it.id) } ?: songB?.song?.duration?.toFloat() ?: 1f

            // Build detected visual grid fallback (prefer visual file, else fall back to sync file)
            val detectedVisualA = if (visualGridA.isNotEmpty()) visualGridA else syncGridA.map { it.toFloat() }
            val detectedVisualB = if (visualGridB.isNotEmpty()) visualGridB else syncGridB.map { it.toFloat() }

            // Resolve Dual Grids using BeatGridNormalizer (phase-preserving)
            val dualA = BeatGridNormalizer.resolveDjGrids(
                detectedGrid = detectedVisualA,
                analysisBpm = analysisBpmA,
                displayBpm = displayBpmA,
                durationSec = durA,
                promoteVisual = false // keep waveform transients visually intact by default
            )

            val dualB = BeatGridNormalizer.resolveDjGrids(
                detectedGrid = detectedVisualB,
                analysisBpm = analysisBpmB,
                displayBpm = displayBpmB,
                durationSec = durB,
                promoteVisual = false
            )

            // Use sync grids for playback (high precision)
            rawGrid1 = dualA.sync.map { it.toDouble() }
            rawGrid2 = dualB.sync.map { it.toDouble() }

            // Use visual grids for waveform drawing (Float)
            val gridForWaveformA = dualA.visual
            val gridForWaveformB = dualB.visual

            // Compute scalar between tracks for visual display matching (unchanged)
            val bpmA = displayBpmA
            val bpmB = displayBpmB
            val rawRatio = if (bpmB > 0f) bpmA / bpmB else 1f

            val candidates = listOf(0.5f, 1.0f, 1.5f, 2.0f, 4.0f)
            val bestMultiplier = candidates.minByOrNull { k ->
                kotlin.math.abs(1f - (rawRatio / k))
            } ?: 1.0f

            trackBGridScalar = bestMultiplier.toDouble()
            initialSpeedB = (rawRatio / bestMultiplier).toDouble()

            // --- Waveform Generation (Keep UI on Floats for performance) ---
            val samplesPerBeat = 64

            _waveformBeatDomain1.value = convertWaveformToBeatDomain(
                pcmA,
                gridForWaveformA,
                durA,
                samplesPerBeat,
                1.0f
            )

            _waveformBeatDomain2.value = convertWaveformToBeatDomain(
                pcmB,
                gridForWaveformB,
                durB,
                samplesPerBeat,
                trackBGridScalar.toFloat()
            )

            updateTransitionDuration()

            withContext(Dispatchers.Main) {
                // Prepare players with local files
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

        if (eqA == null && pA.audioSessionId != 0) {
            try { eqA = Equalizer(0, pA.audioSessionId).apply { enabled = true } } catch (e: Exception) { e.printStackTrace() }
        }
        if (eqB == null && pB.audioSessionId != 0) {
            try { eqB = Equalizer(0, pB.audioSessionId).apply { enabled = true } } catch (e: Exception) { e.printStackTrace() }
        }

        _isPlaying.value = true

        val beatOffsetA = _track1OffsetBeats.value.toDouble()
        val beatOffsetB = _track2OffsetBeats.value.toDouble()

        val zoneFraction = _transitionWidthFraction.value
        val marginFraction = (1f - zoneFraction) / 2f
        val beatsInZone = (_barsCount.value * 4).toDouble()
        val visualDelayBeats = if (zoneFraction > 0f) (marginFraction / zoneFraction) * beatsInZone else 0.0

        val anchorBeatA = beatOffsetA + visualDelayBeats
        val anchorBeatB = beatOffsetB + visualDelayBeats

        val timeAnchorA = getTimestampForBeat(gridA, anchorBeatA)
        val timeAnchorB = getTimestampForBeat(gridB, anchorBeatB / trackBGridScalar)

        val speedA = 1.0f
        val speedB = initialSpeedB.toFloat()
        pA.setPlaybackSpeed(speedA)
        pB.setPlaybackSpeed(speedB)

        val STABILIZATION_MS = 500L
        val prerollSeconds = (PRE_ROLL_MS + STABILIZATION_MS) / 1000.0

        val seekA = (timeAnchorA - prerollSeconds).coerceAtLeast(0.0)
        val seekB = (timeAnchorB - (prerollSeconds * initialSpeedB)).coerceAtLeast(0.0)

        pA.seekTo((seekA * 1000).toLong())
        pB.seekTo((seekB * 1000).toLong())

        pA.volume = 0f
        pB.volume = 0f

        pA.play()
        pB.play()

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val entryBeatA = anchorBeatA
            val transitionDurationBeats = beatsInZone

            val unmuteBeatA = getBeatForTimestamp(gridA, seekA + (STABILIZATION_MS/1000.0))

            val bandsA = eqA?.numberOfBands ?: 0.toShort()
            val bandsB = eqB?.numberOfBands ?: 0.toShort()
            val minEQ = eqA?.bandLevelRange?.get(0) ?: -1500

            var bUnmuted = false

            var nextCorrectionBeat = unmuteBeatA + 16.0 // First correction 16 beats after start

            while (isActive && _isPlaying.value) {
                val posA = pA.currentPosition / 1000.0
                val currentBeatA = getBeatForTimestamp(gridA, posA)

                _playbackBeatMarker.value = currentBeatA.toFloat()

                if (currentBeatA > nextCorrectionBeat) {
                    val posB = pB.currentPosition / 1000.0

                    val relativeProgressBeats = currentBeatA - beatOffsetA
                    val targetBeatB = (relativeProgressBeats + beatOffsetB) / trackBGridScalar
                    val expectedTimeB = getTimestampForBeat(gridB, targetBeatB)

                    val driftSeconds = posB - expectedTimeB

                    if (abs(driftSeconds) > 0.005) {
                        val correctionFactor =
                            (1.0 - (driftSeconds.coerceIn(-0.02, 0.02) * 0.05)).toFloat()
                        val newSpeed = (initialSpeedB * correctionFactor).toFloat()
                        pB.setPlaybackSpeed(newSpeed)

                        launch {
                            delay(500)
                            if (isActive && _isPlaying.value) {
                                pB.setPlaybackSpeed(initialSpeedB.toFloat())
                            }
                        }
                    }

                    nextCorrectionBeat += 16.0
                }
                val progress = if (transitionDurationBeats > 0)
                    ((currentBeatA - entryBeatA) / transitionDurationBeats).toFloat()
                else 0f

                if (progress < 0f) {
                    if (currentBeatA >= unmuteBeatA) {
                        pA.volume = 1f
                    } else {
                        pA.volume = 0f
                    }
                    pB.volume = 0f
                    resetEQ(eqA)
                    resetEQ(eqB)
                } else if (progress <= 1f) {
                    val stateA = TransitionMixer.getMixState("A", progress, _overlapMode.value, _eqMode.value, _effectMode.value)
                    val stateB = TransitionMixer.getMixState("B", progress, _overlapMode.value, _eqMode.value, _effectMode.value)

                    pA.volume = stateA.volume
                    pB.volume = stateB.volume

                    applyDeckStateToEQ(eqA, stateA, minEQ, bandsA, _effectMode.value)
                    applyDeckStateToEQ(eqB, stateB, minEQ, bandsB, _effectMode.value)

                    if (!bUnmuted) {
                        bUnmuted = true
                    }
                } else {
                    pA.volume = 0f
                    pB.volume = 1f
                    resetEQ(eqB)
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
        resetEQ(eqA)
        resetEQ(eqB)
        playbackJob?.cancel()
        _playbackBeatMarker.value = null
    }

    private fun resetEQ(eq: Equalizer?) {
        if (eq == null) return
        try {
            for (i in 0 until eq.numberOfBands) eq.setBandLevel(i.toShort(), 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyDeckStateToEQ(
        eq: Equalizer?,
        state: com.dd3boh.outertune.utils.DeckState,
        minEQ: Short,
        bands: Short,
        effectMode: String
    ) {
        if (eq == null || bands < 1) return

        try {
            val bassLevel = state.bass
            val bassCut = (minEQ * (1f - bassLevel)).toInt().toShort()

            eq.setBandLevel(0.toShort(), bassCut)
            if (bands > 1) {
                eq.setBandLevel(1.toShort(), (bassCut * 0.8).toInt().toShort())
            }

            val isLPF = effectMode.contains("Low pass")
            val isHPF = effectMode.contains("High Pass")
            val filterLevel = state.filterHigh

            if (isLPF) {
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()
                val lastBand = (bands - 1).toShort()
                eq.setBandLevel(lastBand, cut)
                if (bands > 2) {
                    val prevBand = (bands - 2).toShort()
                    eq.setBandLevel(prevBand, (cut * 0.7).toInt().toShort())
                }
            } else if (isHPF) {
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()
                val currentBand0 = eq.getBandLevel(0.toShort())
                if (cut < currentBand0) eq.setBandLevel(0.toShort(), cut)
                if (bands > 1) {
                    val currentBand1 = eq.getBandLevel(1.toShort())
                    if (cut < currentBand1) eq.setBandLevel(1.toShort(), cut)
                }
                if (bands > 2) {
                    eq.setBandLevel(2.toShort(), (cut * 0.5).toInt().toShort())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun convertWaveformToBeatDomain(
        waveform: FloatArray,
        grid: List<Float>,
        durationSec: Float,
        samplesPerBeat: Int,
        scalar: Float
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

                val originalBeatPos = beatIndex.toFloat() + beatFraction
                val finalBeatIndex = originalBeatPos * scalar

                out.add(BeatSample(finalBeatIndex, maxAmp))
            }
        }
        return out
    }

    private fun updateTransitionDuration() {
        val grid = rawGrid1
        if (grid.size < 2) return

        val beats = _barsCount.value * 4
        val interval = grid[1] - grid[0]
        _transitionDurationSeconds.value = (beats * interval).toFloat()
    }

    // --- Double Precision Helpers ---

    private fun getTimestampForBeat(grid: List<Double>, beatIndex: Double): Double {
        if (grid.isEmpty()) return 0.0
        val lastIdx = grid.size - 1
        if (beatIndex < 0) {
            val avgStep = if (grid.size > 1) (grid[1] - grid[0]) else 0.5
            return (grid[0] + beatIndex * avgStep).coerceAtLeast(0.0)
        }
        if (beatIndex > lastIdx) {
            val avgStep = if (grid.size > 1) (grid[lastIdx] - grid[lastIdx - 1]) else 0.5
            return grid[lastIdx] + (beatIndex - lastIdx) * avgStep
        }
        val idx = beatIndex.toInt()
        val t1 = grid[idx]
        val t2 = if (idx + 1 < grid.size) grid[idx + 1] else t1 + 0.5
        return t1 + (t2 - t1) * (beatIndex - idx)
    }

    private fun getBeatForTimestamp(grid: List<Double>, time: Double): Double {
        if (grid.isEmpty()) return 0.0
        val ip = grid.binarySearch(time)
        if (ip >= 0) return ip.toDouble()

        val idx = -(ip + 1) - 1

        if (idx < 0) {
            val step = if (grid.size > 1) grid[1] - grid[0] else 0.5
            return (time - grid[0]) / step
        }
        if (idx >= grid.size - 1) {
            val step = if (grid.size > 1) grid[grid.size - 1] - grid[grid.size - 2] else 0.5
            return (grid.size - 1) + (time - grid.last()) / step
        }

        val t1 = grid[idx]
        val t2 = grid[idx + 1]
        val fraction = (time - t1) / (t2 - t1)
        return idx + fraction
    }

    private fun loadExactDuration(id: String): Float? {
        val file = File(context.cacheDir, "analysis_data/${id}_metadata.dat")
        return if (file.exists()) file.readText().toFloatOrNull() else null
    }

    private fun loadWaveform(path: String?, id: String) = File(path ?: "${context.cacheDir}/analysis_data/${id}_waveform.dat")
        .takeIf { it.exists() }?.readText()?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: FloatArray(0)

    private fun loadBeatGridFloat(path: String?, id: String, suffix: String): List<Float> {
        val file = if (path != null) File(path) else File("${context.cacheDir}/analysis_data/${id}_beats${suffix}.dat")
        return file.takeIf { it.exists() }?.readText()?.split(",")?.mapNotNull { it.toFloatOrNull() }?.map { it / 1000f } ?: emptyList()
    }

    private fun loadBeatGridDouble(path: String?, id: String): List<Double> {
        val file = if (path != null) File(path) else File("${context.cacheDir}/analysis_data/${id}_beats_sync.dat")
        return file.takeIf { it.exists() }?.readText()?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.map { it / 1000.0 } ?: emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        eqA?.release()
        eqB?.release()
        playerA?.release()
        playerB?.release()
    }
}
