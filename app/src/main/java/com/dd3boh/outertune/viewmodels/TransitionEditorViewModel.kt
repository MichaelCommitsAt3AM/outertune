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
import com.dd3boh.outertune.utils.DJHelper
import com.dd3boh.outertune.utils.TransitionMixer
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
        // Initialize Players without EQ first
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
    // Update loadData function:
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
            var syncGridA = emptyList<Float>()
            var syncGridB = emptyList<Float>()

            songA?.let {
                pcmA = loadWaveform(it.song.waveformPath, it.id)
                // Load BOTH grids
                visualGridA = loadBeatGrid(null, it.id, suffix = "_visual")
                syncGridA = loadBeatGrid(it.song.beatGridPath, it.id)
            }
            songB?.let {
                pcmB = loadWaveform(it.song.waveformPath, it.id)
                visualGridB = loadBeatGrid(null, it.id, suffix = "_visual")
                syncGridB = loadBeatGrid(it.song.beatGridPath, it.id)
            }

            // Store SYNC grid for playback (perfect timing)
            rawGrid1 = syncGridA
            rawGrid2 = syncGridB

            val durA = songA?.let { loadExactDuration(it.id) }
                ?: songA?.song?.duration?.toFloat() ?: 1f

            val durB = songB?.let { loadExactDuration(it.id) }
                ?: songB?.song?.duration?.toFloat() ?: 1f

            val bpmA = songA?.song?.bpm ?: 120f
            val bpmB = songB?.song?.bpm ?: 120f
            initialSpeedB = if (bpmB > 0) bpmA / bpmB else 1f

            // Generate Beat-Domain Waveforms using VISUAL grid
            // This shows the waveform aligned to actual transients
            val samplesPerBeat = 64

            _waveformBeatDomain1.value = convertWaveformToBeatDomain(
                pcmA,
                visualGridA, // Use visual grid for accurate waveform rendering
                durA,
                samplesPerBeat
            )
            _waveformBeatDomain2.value = convertWaveformToBeatDomain(
                pcmB,
                visualGridB,
                durB,
                samplesPerBeat
            )

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

        // --- 1. INITIALIZE AUDIO FX (Lazy Load) ---
        // We initialize here because audioSessionId is guaranteed to be valid after prepare/interaction
        // If we try this in 'init', audioSessionId is often 0 (invalid)
        if (eqA == null && pA.audioSessionId != 0) {
            try { eqA = Equalizer(0, pA.audioSessionId).apply { enabled = true } } catch (e: Exception) { e.printStackTrace() }
        }
        if (eqB == null && pB.audioSessionId != 0) {
            try { eqB = Equalizer(0, pB.audioSessionId).apply { enabled = true } } catch (e: Exception) { e.printStackTrace() }
        }

        _isPlaying.value = true

        // --- CALCULATE SYNC POINTS ---
        val beatOffsetA = _track1OffsetBeats.value
        val beatOffsetB = _track2OffsetBeats.value

        val zoneFraction = _transitionWidthFraction.value
        val marginFraction = (1f - zoneFraction) / 2f
        val beatsInZone = _barsCount.value * 4f
        val visualDelayBeats = if (zoneFraction > 0f) (marginFraction / zoneFraction) * beatsInZone else 0f

        // The "Anchor" is the start of the Transition Zone (Green Box)
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
        // Seek 3.5s before the transition to let audio engine warm up
        val STABILIZATION_MS = 500L
        val prerollSeconds = (PRE_ROLL_MS + STABILIZATION_MS) / 1000f

        val seekA = (timeAnchorA - prerollSeconds).coerceAtLeast(0f)
        val seekB = (timeAnchorB - (prerollSeconds * speedB)).coerceAtLeast(0f)

        pA.seekTo((seekA * 1000).toLong())
        pB.seekTo((seekB * 1000).toLong())

        // 4. Start SILENTLY (Volume will be handled in loop)
        pA.volume = 0f
        pB.volume = 0f

        pA.play()
        pB.play()

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val entryBeatA = anchorBeatA
            val transitionDurationBeats = beatsInZone

            // Pre-roll calculation: We unmute A exactly PRE_ROLL_MS before the Anchor
            val bpmA = _track1.value?.song?.bpm ?: 120f
            val beatsPerSec = bpmA / 60f
            val preRollBeats = (PRE_ROLL_MS / 1000f) * beatsPerSec
            val unmuteBeatA = anchorBeatA - preRollBeats

            // Cache EQ ranges for performance
            val bandsA = eqA?.numberOfBands ?: 0.toShort()
            val bandsB = eqB?.numberOfBands ?: 0.toShort()
            val minEQ = eqA?.bandLevelRange?.get(0) ?: -1500

            var bUnmuted = false

            while (isActive && _isPlaying.value) {
                // Monitor position based on Track A (Master)
                val posA = pA.currentPosition / 1000f
                val currentBeatA = DJHelper.timeToBeat(gridA, posA)

                _playbackBeatMarker.value = currentBeatA

                // Calculate Progress (0.0 to 1.0) inside the Transition Zone
                // (currentBeatA - entryBeatA) gives 0 at the start of the green box
                val progress = if (transitionDurationBeats > 0)
                    (currentBeatA - entryBeatA) / transitionDurationBeats
                else 0f

                // --- MIXING LOGIC ---

                if (progress < 0f) {
                    // === PRE-TRANSITION (Pre-roll) ===

                    // Track A: Handle "Stabilization" Unmute
                    // It starts silent (-3.5s) and unmutes at (-3.0s)
                    if (currentBeatA >= unmuteBeatA) {
                        pA.volume = 1f
                    } else {
                        pA.volume = 0f
                    }

                    // Track B: Silent
                    pB.volume = 0f

                    // Reset EQs to clean state
                    resetEQ(eqA)
                    resetEQ(eqB)

                } else if (progress <= 1f) {
                    // === INSIDE TRANSITION ZONE ===

                    // 1. Get Target States from helper
                    val stateA = TransitionMixer.getMixState("A", progress, _overlapMode.value, _eqMode.value, _effectMode.value)
                    val stateB = TransitionMixer.getMixState("B", progress, _overlapMode.value, _eqMode.value, _effectMode.value)

                    // 2. Apply Volume
                    pA.volume = stateA.volume
                    pB.volume = stateB.volume

                    // 3. Apply EQ & Effects
                    applyDeckStateToEQ(eqA, stateA, minEQ, bandsA, _effectMode.value)
                    applyDeckStateToEQ(eqB, stateB, minEQ, bandsB, _effectMode.value)

                    // 4. Sync Correction
                    if (!bUnmuted) {
                        bUnmuted = true
                    }

                } else {
                    // === POST-TRANSITION ===

                    // Track A: Done
                    pA.volume = 0f

                    // Track B: Full
                    pB.volume = 1f

                    // Reset EQ for B (it's now the main track)
                    resetEQ(eqB)
                }

                delay(20) // Update loop ~50fps
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

    // Reset EQ (flat)
    private fun resetEQ(eq: Equalizer?) {
        if (eq == null) return
        try {
            for (i in 0 until eq.numberOfBands) eq.setBandLevel(i.toShort(), 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Helper to apply mixing state to EQ
    private fun applyDeckStateToEQ(
        eq: Equalizer?,
        state: com.dd3boh.outertune.utils.DeckState,
        minEQ: Short,
        bands: Short,
        effectMode: String
    ) {
        if (eq == null || bands < 1) return

        try {
            // 1. BASS LOGIC (Bass Kill)
            // Standard EQ limits (-15dB) are weak for a kill. We cut Band 0 AND Band 1.
            val bassLevel = state.bass // 1.0 = Full, 0.0 = Cut
            val bassCut = (minEQ * (1f - bassLevel)).toInt().toShort()

            eq.setBandLevel(0.toShort(), bassCut)
            if (bands > 1) {
                // Cut Low-Mids by 80% of the bass cut amount to make it sound cleaner
                eq.setBandLevel(1.toShort(), (bassCut * 0.8).toInt().toShort())
            }

            // 2. FILTER LOGIC
            val isLPF = effectMode.contains("Low pass")
            val isHPF = effectMode.contains("High Pass")
            val filterLevel = state.filterHigh // 1.0 = Open, 0.0 = Closed

            if (isLPF) {
                // Low Pass: Cut High Frequencies
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()
                val lastBand = (bands - 1).toShort()

                // Cut highest band
                eq.setBandLevel(lastBand, cut)

                // Cut second highest band significantly
                if (bands > 2) {
                    val prevBand = (bands - 2).toShort()
                    eq.setBandLevel(prevBand, (cut * 0.7).toInt().toShort())
                }
            } else if (isHPF) {
                // High Pass: Cut Low Frequencies
                // This overlaps with Bass logic. We apply the stronger of the two cuts.
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()

                // Band 0
                val currentBand0 = eq.getBandLevel(0.toShort())
                if (cut < currentBand0) eq.setBandLevel(0.toShort(), cut)

                // Band 1
                if (bands > 1) {
                    val currentBand1 = eq.getBandLevel(1.toShort())
                    if (cut < currentBand1) eq.setBandLevel(1.toShort(), cut)
                }

                // Band 2 (Mids) - optional for stronger HPF effect
                if (bands > 2) {
                    eq.setBandLevel(2.toShort(), (cut * 0.5).toInt().toShort())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * SNAPS BTrack timestamps to the nearest loud amplitude peak.
     */
    private fun refineBeatGrid(
        roughGrid: List<Float>,
        waveform: FloatArray,
        durationSec: Float
    ): List<Float> {
        if (waveform.isEmpty() || roughGrid.isEmpty() || durationSec <= 0) return roughGrid

        val sampleRate = waveform.size / durationSec
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

    private fun loadBeatGrid(
        path: String?,
        id: String,
        suffix: String = "_sync"
    ): List<Float> {
        val file = if (path != null) {
            File(path)
        } else {
            File("${context.cacheDir}/analysis_data/${id}_beats${suffix}.dat")
        }

        return file.takeIf { it.exists() }
            ?.readText()
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.map { it / 1000f }
            ?: emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        eqA?.release()
        eqB?.release()
        playerA?.release()
        playerB?.release()
    }
}