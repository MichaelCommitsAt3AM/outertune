package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.TransitionEntity
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

// Data class for the visual waveform
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

    // Derived beat indices for UI markers
    // We derive this directly from the waveform samples to ensure 1:1 visual alignment
    val beatMarkers: StateFlow<List<BeatGridMarker>> =
        combine(waveformBeatDomain1, _track1) { samples, song ->
            if (samples.isEmpty() || song == null) emptyList()
            else {
                val maxBeat = samples.lastOrNull()?.beatIndex ?: 0f
                val timeSig = song.song.timeSignature
                val offset = song.song.downbeatOffset

                // Generate markers for every integer beat index covered by the waveform
                (0..maxBeat.toInt()).map { index ->
                    // Determine if this is a Downbeat (Beat 1)
                    val isOne = (index % timeSig) == offset

                    BeatGridMarker(
                        beatIndex = index.toFloat(),
                        isDownbeat = isOne,
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

    // Internal State for Playback Sync (Double Precision)
    private var initialSpeedB = 1.0
    private var currentScreenWidthPx: Float = 0f
    private var trackBGridScalar = 1.0

    // Cache raw grids (Double Precision) for time lookups
    private var rawGrid1: List<Double> = emptyList()
    private var rawGrid2: List<Double> = emptyList()

    private val PRE_ROLL_MS = 3000L

    init { viewModelScope.launch(Dispatchers.Main) { setupPlayers() } }

    private fun setupPlayers() {
        if (playerA == null) {
            playerA = ExoPlayer.Builder(context).build().apply {
                volume = 1f
                // ADD THIS
                setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            }
        }
        if (playerB == null) {
            playerB = ExoPlayer.Builder(context).build().apply {
                volume = 1f
                // ADD THIS
                setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            }
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

            val durA = songA?.let { loadExactDuration(it.id) } ?: songA?.song?.duration?.toDouble() ?: 1.0
            val durB = songB?.let { loadExactDuration(it.id) } ?: songB?.song?.duration?.toDouble() ?: 1.0

            // 1. Resolve Grids
            val dualA = BeatGridNormalizer.resolveDjGrids(
                detectedGrid = loadBeatGridDouble(songA?.song?.beatGridPath, songAId).map { it.toFloat() },
                analysisBpm = songA?.song?.bpm ?: 120f,
                displayBpm = songA?.song?.displayBpm ?: 120f,
                durationSec = durA.toFloat()
            )
            val dualB = BeatGridNormalizer.resolveDjGrids(
                detectedGrid = loadBeatGridDouble(songB?.song?.beatGridPath, songBId).map { it.toFloat() },
                analysisBpm = songB?.song?.bpm ?: 120f,
                displayBpm = songB?.song?.displayBpm ?: 120f,
                durationSec = durB.toFloat()
            )

            rawGrid1 = dualA.sync.map { it.toDouble() }
            rawGrid2 = dualB.sync.map { it.toDouble() }

            // === GRID DIAGNOSTICS ===
            Log.d("GRID_DEBUG", """
            === Raw Grid Data ===
            Track A: ${rawGrid1.size} beats
            First 5 beats: ${rawGrid1.take(5).map { String.format("%.3f", it) }}
            Last 5 beats: ${rawGrid1.takeLast(5).map { String.format("%.3f", it) }}
            
            Track B: ${rawGrid2.size} beats  
            First 5 beats: ${rawGrid2.take(5).map { String.format("%.3f", it) }}
            Last 5 beats: ${rawGrid2.takeLast(5).map { String.format("%.3f", it) }}
        """.trimIndent())

            // Check if intervals are consistent
            val intervalsA = rawGrid1.zipWithNext { a, b -> b - a }
            val intervalsB = rawGrid2.zipWithNext { a, b -> b - a }

            val avgIntervalA = intervalsA.average()
            val avgIntervalB = intervalsB.average()
            val stdDevA = kotlin.math.sqrt(intervalsA.map { (it - avgIntervalA).let { d -> d * d } }.average())
            val stdDevB = kotlin.math.sqrt(intervalsB.map { (it - avgIntervalB).let { d -> d * d } }.average())

            Log.d("GRID_DEBUG", """
            === Grid Interval Statistics ===
            Track A: avg=${String.format("%.4f", avgIntervalA)}s, stdDev=${String.format("%.4f", stdDevA)}s
            Track B: avg=${String.format("%.4f", avgIntervalB)}s, stdDev=${String.format("%.4f", stdDevB)}s
            
            First 10 intervals A: ${intervalsA.take(10).map { String.format("%.4f", it) }}
            Last 10 intervals A: ${intervalsA.takeLast(10).map { String.format("%.4f", it) }}
            
            First 10 intervals B: ${intervalsB.take(10).map { String.format("%.4f", it) }}
            Last 10 intervals B: ${intervalsB.takeLast(10).map { String.format("%.4f", it) }}
        """.trimIndent())

            // 2. Get BPMs for comparison
            val bpmA = songA?.song?.displayBpm ?: songA?.song?.bpm ?: 120f
            val bpmB = songB?.song?.displayBpm ?: songB?.song?.bpm ?: 120f

            Log.d("DJ_DEBUG", "Track A BPM: $bpmA, Track B BPM: $bpmB")

            // 3. FIXED: Check if BPMs are close enough to tempo-match
            val bpmDifference = kotlin.math.abs(bpmA - bpmB)

            if (bpmDifference <= 15f) {
                // BPMs are close - force Track B to play at Track A's tempo
                Log.d("DJ_DEBUG", "BPM difference ${bpmDifference} <= 15, tempo-matching to Track A")

                // Speed ratio is simply BPM ratio (no interval calculation needed)
                initialSpeedB = (bpmB / bpmA).toDouble()
                trackBGridScalar = 1.0 // No grid scaling needed

            } else {
                // BPMs are too different - use physical interval matching (original logic)
                Log.d("DJ_DEBUG", "BPM difference ${bpmDifference} > 15, using interval matching")

                val avgIntervalA = if (rawGrid1.size > 1)
                    (rawGrid1.last() - rawGrid1.first()) / (rawGrid1.size - 1) else 0.5
                val avgIntervalB = if (rawGrid2.size > 1)
                    (rawGrid2.last() - rawGrid2.first()) / (rawGrid2.size - 1) else 0.5

                val rawRatio = if (avgIntervalA > 0 && avgIntervalB > 0)
                    avgIntervalB / avgIntervalA else 1.0

                // Handle double/half time
                val candidates = listOf(0.5, 1.0, 1.5, 2.0, 4.0)
                val bestMultiplier = candidates.minByOrNull { k ->
                    kotlin.math.abs(1.0 - (rawRatio / k))
                } ?: 1.0

                trackBGridScalar = bestMultiplier
                initialSpeedB = rawRatio / bestMultiplier
            }

            Log.d("DJ_DEBUG", "Final: initialSpeedB=$initialSpeedB, gridScalar=$trackBGridScalar")

            // 4. Waveform Generation (use the grid scalar for visual alignment)
            val samplesPerBeat = 64
            _waveformBeatDomain1.value = convertWaveformToBeatDomain(
                loadWaveform(songA?.song?.waveformPath, songAId),
                rawGrid1, durA, samplesPerBeat, 1.0
            )
            _waveformBeatDomain2.value = convertWaveformToBeatDomain(
                loadWaveform(songB?.song?.waveformPath, songBId),
                rawGrid2, durB, samplesPerBeat, trackBGridScalar
            )

            updateTransitionDuration()

            withContext(Dispatchers.Main) {
                setupPlayers()
                songA?.song?.localPath?.let {
                    playerA?.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(it))))
                    playerA?.prepare()
                }
                songB?.song?.localPath?.let {
                    playerB?.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(it))))
                    playerB?.prepare()
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

        // 1. Initialize EQs
        if (eqA == null && pA.audioSessionId != 0) {
            try { eqA = Equalizer(0, pA.audioSessionId).apply { enabled = true } }
            catch (e: Exception) { Log.e("FX", "EQ A Init Failed", e) }
        }
        if (eqB == null && pB.audioSessionId != 0) {
            try { eqB = Equalizer(0, pB.audioSessionId).apply { enabled = true } }
            catch (e: Exception) { Log.e("FX", "EQ B Init Failed", e) }
        }

        _isPlaying.value = true

        // 2. Setup Anchors
        val beatOffsetA = _track1OffsetBeats.value.toDouble()
        val beatOffsetB = _track2OffsetBeats.value.toDouble()
        val zoneFraction = _transitionWidthFraction.value
        val marginFraction = (1f - zoneFraction) / 2f
        val beatsInZone = (_barsCount.value * 4).toDouble()
        val visualMarginBeats = if (zoneFraction > 0f) (marginFraction / zoneFraction) * beatsInZone else 0.0

        val anchorBeatA = beatOffsetA + visualMarginBeats
        val internalBeatB = if (trackBGridScalar == 1.0) beatOffsetB + visualMarginBeats
        else (beatOffsetB + visualMarginBeats) / trackBGridScalar

        val timeAnchorA = getTimestampForBeat(gridA, anchorBeatA)
        val timeAnchorB = getTimestampForBeat(gridB, internalBeatB)

        // 3. Set Initial Speeds & Seek
        pA.setPlaybackSpeed(1.0f)
        pB.setPlaybackSpeed(initialSpeedB.toFloat())

        val STABILIZATION_MS = 500L
        val prerollSeconds = (PRE_ROLL_MS + STABILIZATION_MS) / 1000.0
        val seekA = (timeAnchorA - prerollSeconds).coerceAtLeast(0.0)

        val prerollB = if (trackBGridScalar == 1.0) prerollSeconds else prerollSeconds * initialSpeedB
        val effectivePrerollB = if (timeAnchorB < prerollB) timeAnchorB * 0.9 else prerollB
        val seekB = (timeAnchorB - effectivePrerollB).coerceAtLeast(0.0)

        val actualSeekA = if (effectivePrerollB < prerollB) {
            val prerollRatio = effectivePrerollB / prerollB
            (timeAnchorA - (prerollSeconds * prerollRatio)).coerceAtLeast(0.0)
        } else {
            seekA
        }

        pA.seekTo((actualSeekA * 1000).toLong())
        pB.seekTo((seekB * 1000).toLong())

        pA.volume = 0f
        pB.volume = 0f

        pA.play()
        pB.play()

        playbackJob?.cancel()

        // CRITICAL FIX: Run on Main thread to prevent crash.
        // The arithmetic is fast enough that it won't block the UI.
        playbackJob = viewModelScope.launch(Dispatchers.Main) {
            val entryBeatA = anchorBeatA
            val transitionDurationBeats = beatsInZone
            val unmuteBeatA = getBeatForTimestamp(gridA, actualSeekA + (STABILIZATION_MS / 1000.0))

            val bandsA = eqA?.numberOfBands ?: 0.toShort()
            val bandsB = eqB?.numberOfBands ?: 0.toShort()
            val minEQ = eqA?.bandLevelRange?.get(0) ?: -1500

            var isLeaderActive = false
            var lastPosA = -1.0

            var smoothedDrift = 0.0
            var currentAppliedSpeed = initialSpeedB.toFloat()

            while (isActive && _isPlaying.value) {
                // Safety check inside loop
                if (playerA == null || playerB == null) break

                val posA = pA.currentPosition / 1000.0
                val posB = pB.currentPosition / 1000.0

                // 1. STARTUP LATCH (Fixes end-of-song sync issue)
                if (!isLeaderActive) {
                    if (posA > actualSeekA + 0.05 && posA != lastPosA) {
                        isLeaderActive = true

                        // Startup Snap
                        val currentBeatA = getBeatForTimestamp(gridA, posA)
                        val elapsedBeatsA = currentBeatA - anchorBeatA

                        val expectedBeatB = if (trackBGridScalar == 1.0) internalBeatB + elapsedBeatsA
                        else (beatOffsetB + visualMarginBeats + elapsedBeatsA) / trackBGridScalar

                        val expectedTimeB = getTimestampForBeat(gridB, expectedBeatB)

                        if (kotlin.math.abs(posB - expectedTimeB) > 0.03) {
                            pB.seekTo((expectedTimeB * 1000).toLong())
                        }
                    } else {
                        lastPosA = posA
                        delay(20)
                        continue
                    }
                }
                lastPosA = posA

                // 2. DRIFT CALCULATION
                val currentBeatA = getBeatForTimestamp(gridA, posA)
                _playbackBeatMarker.value = currentBeatA.toFloat()

                val elapsedBeatsA = currentBeatA - anchorBeatA
                val targetBeatB_internal = if (trackBGridScalar == 1.0) internalBeatB + elapsedBeatsA
                else (beatOffsetB + visualMarginBeats + elapsedBeatsA) / trackBGridScalar

                val targetTimeB = getTimestampForBeat(gridB, targetBeatB_internal)
                val rawDrift = posB - targetTimeB

                // Low-pass filter to ignore single-frame jitter
                smoothedDrift = (smoothedDrift * 0.7) + (rawDrift * 0.3)

                // 3. SPEED THROTTLING (Fixes choppy audio)
                val newSpeed = when {
                    kotlin.math.abs(smoothedDrift) > 0.100 -> {
                        // Drift > 100ms: Hard Sync
                        pB.seekTo((targetTimeB * 1000).toLong())
                        smoothedDrift = 0.0
                        initialSpeedB.toFloat()
                    }
                    kotlin.math.abs(smoothedDrift) > 0.010 -> {
                        // Drift > 10ms: Soft Correction
                        val correction = (-smoothedDrift * 0.08).coerceIn(-0.05, 0.05)
                        (initialSpeedB + correction).toFloat()
                    }
                    else -> initialSpeedB.toFloat()
                }

                // DEAD BAND: Only update ExoPlayer if speed changed by > 0.2%
                // This prevents the audio engine from stuttering due to constant updates
                if (kotlin.math.abs(newSpeed - currentAppliedSpeed) > 0.002f) {
                    pB.setPlaybackSpeed(newSpeed)
                    currentAppliedSpeed = newSpeed
                }

                // 4. MIXER AUTOMATION
                val progress = if (transitionDurationBeats > 0)
                    ((currentBeatA - entryBeatA) / transitionDurationBeats).toFloat()
                else 0f

                if (progress < 0f) {
                    pA.volume = if (currentBeatA >= unmuteBeatA) 1f else 0f
                    pB.volume = 0f
                    resetEQ(eqA); resetEQ(eqB)
                } else if (progress <= 1f) {
                    val stateA = TransitionMixer.getMixState("A", progress, _overlapMode.value, _eqMode.value, _effectMode.value)
                    val stateB = TransitionMixer.getMixState("B", progress, _overlapMode.value, _eqMode.value, _effectMode.value)

                    pA.volume = stateA.volume
                    pB.volume = stateB.volume

                    applyDeckStateToEQ(eqA, stateA, minEQ, bandsA, _effectMode.value)
                    applyDeckStateToEQ(eqB, stateB, minEQ, bandsB, _effectMode.value)
                } else {
                    pA.volume = 0f
                    pB.volume = 1f
                    resetEQ(eqB)
                }

                // Update roughly 30 times a second
                delay(33)
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
        } catch (e: Exception) { e.printStackTrace() }
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * CONVERT WAVEFORM TO BEAT DOMAIN
     * FIXED: Uses Double precision for all time calculations to prevent drift.
     */
    private fun convertWaveformToBeatDomain(
        waveform: FloatArray,
        grid: List<Double>,     // Input grid must be Double
        durationSec: Double,    // Duration must be Double
        samplesPerBeat: Int,
        scalar: Double
    ): List<BeatSample> {
        if (waveform.isEmpty() || grid.size < 2 || durationSec <= 0.0) return emptyList()
        val out = ArrayList<BeatSample>()
        val size = waveform.size

        // Calculate sample rate (indices per second) using high precision
        val indicesPerSecond = size.toDouble() / durationSec

        for (beatIndex in 0 until grid.size - 1) {
            val tGridStart = grid[beatIndex]
            val tGridEnd = grid[beatIndex + 1]
            val intervalDuration = tGridEnd - tGridStart
            var peakSampleIndex = -1
            var maxAmpInBeat = 0f

            // Safety check for empty/invalid intervals
            if (intervalDuration <= 0.000001) {
                // Just emit zeroes to keep index alignment
                for (i in 0 until samplesPerBeat) {
                    val originalBeatPos = beatIndex.toDouble() + (i.toDouble() / samplesPerBeat)
                    out.add(BeatSample((originalBeatPos * scalar).toFloat(), 0f))
                }
                continue
            }

            for (i in 0 until samplesPerBeat) {
                // Calculate exact time range for this sub-beat slice
                val beatFractionStart = i.toDouble() / samplesPerBeat
                val beatFractionEnd = (i + 1).toDouble() / samplesPerBeat

                val timeStart = tGridStart + (beatFractionStart * intervalDuration)
                val timeEnd = tGridStart + (beatFractionEnd * intervalDuration)

                var maxAmp = 0f

                // Map time range to array indices
                if (timeEnd > 0.0 && timeStart < durationSec) {
                    val validTimeStart = timeStart.coerceAtLeast(0.0)
                    val validTimeEnd = timeEnd.coerceAtMost(durationSec)

                    val idxStart = (validTimeStart * indicesPerSecond).toInt().coerceIn(0, size - 1)
                    val idxEnd = (validTimeEnd * indicesPerSecond).toInt().coerceIn(0, size)

                    // Find max amplitude in this slice
                    for (k in idxStart until idxEnd) {
                        if (k < size) {
                            val amp = abs(waveform[k])
                            if (amp > maxAmp) maxAmp = amp

                            // Capture global max for the whole beat to log specifically on the downbeat
                            if (amp > maxAmpInBeat) {
                                maxAmpInBeat = amp
                                peakSampleIndex = i // Record which "slice" (0-63) holds the peak
                            }
                        }
                    }
                }

                // Final output position
                val originalBeatPos = beatIndex.toDouble() + beatFractionStart
                val finalBeatIndex = originalBeatPos * scalar

                out.add(BeatSample(finalBeatIndex.toFloat(), maxAmp))
            }

            // Log only on likely kicks (high amplitude) to avoid noise
            if (maxAmpInBeat > 0.5f) {
                // We expect the kick to be at index 0 (start of beat).
                // If it's at 32, it's in the middle of the beat (off-beat).
                Log.d("WaveformAlign", "Beat $beatIndex: Peak Amp at slice $peakSampleIndex/$samplesPerBeat (Amp: $maxAmpInBeat)")
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

    // --- Double Precision Math Helpers ---

    private fun getTimestampForBeat(grid: List<Double>, beatIndex: Double): Double {
        if (grid.isEmpty()) return 0.0
        val lastIdx = grid.size - 1

        // Extrapolate before start
        if (beatIndex < 0) {
            val avgStep = if (grid.size > 1) (grid[1] - grid[0]) else 0.5
            return (grid[0] + beatIndex * avgStep).coerceAtLeast(0.0)
        }
        // Extrapolate after end
        if (beatIndex > lastIdx) {
            val avgStep = if (grid.size > 1) (grid[lastIdx] - grid[lastIdx - 1]) else 0.5
            return grid[lastIdx] + (beatIndex - lastIdx) * avgStep
        }

        // Interpolate inside grid
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

    fun saveTransition(onComplete: () -> Unit) {
        val songA = track1.value
        val songB = track2.value
        val gridA = rawGrid1
        val gridB = rawGrid2

        if (songA == null || songB == null || gridA.isEmpty() || gridB.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Calculate the timestamps exactly as they are calculated in startPreview()
            //    to ensure What You Hear Is What You Save.

            val beatOffsetA = _track1OffsetBeats.value.toDouble()
            val beatOffsetB = _track2OffsetBeats.value.toDouble()
            val zoneFraction = _transitionWidthFraction.value
            val beatsInZone = (_barsCount.value * 4).toDouble()

            // Calculate how many beats act as the left-side margin before the green box
            val marginFraction = (1f - zoneFraction) / 2f
            val visualMarginBeats = if (zoneFraction > 0f) (marginFraction / zoneFraction) * beatsInZone else 0.0

            // The 'Anchor Beat' is the exact beat index where the Transition Zone (Green Box) starts
            val anchorBeatA = beatOffsetA + visualMarginBeats
            val rawAnchorBeatB = beatOffsetB + visualMarginBeats

            // Adjust Beat B for grid scaling (if tempo sync was needed)
            val internalBeatB = if (trackBGridScalar == 1.0) rawAnchorBeatB
            else rawAnchorBeatB / trackBGridScalar

            // Convert Beats to Milliseconds using the Double Precision grids
            val exitPointMs = (getTimestampForBeat(gridA, anchorBeatA) * 1000).toLong()
            val entryPointMs = (getTimestampForBeat(gridB, internalBeatB) * 1000).toLong()

            // Calculate actual duration in MS based on current grid A speed
            val durationMs = (_transitionDurationSeconds.value * 1000).toLong()

            // 2. Create the Entity
            val transition = TransitionEntity(
                fromSongId = songA.id,
                toSongId = songB.id,
                exitPointMs = exitPointMs.coerceAtLeast(0),
                entryPointMs = entryPointMs.coerceAtLeast(0),
                durationMs = durationMs,
                durationBeats = _barsCount.value * 4,
                syncTempo = true, // We generally force sync in this editor
                type = TransitionEntity.TYPE_MANUAL,

                // Save specific FX settings
                overlapMode = _overlapMode.value,
                eqMode = _eqMode.value,
                effectMode = _effectMode.value
            )

            // 3. Save to DB
            database.transitionDao().insert(transition)

            Log.d("TransitionEditor", "Saved Transition: A[${exitPointMs}ms] -> B[${entryPointMs}ms] (${_eqMode.value})")

            // 4. Close Screen
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    // --- Disk IO (Double Precision) ---

    private fun loadExactDuration(id: String): Double? {
        val file = File(context.cacheDir, "analysis_data/${id}_metadata.dat")
        return if (file.exists()) file.readText().toDoubleOrNull() else null
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