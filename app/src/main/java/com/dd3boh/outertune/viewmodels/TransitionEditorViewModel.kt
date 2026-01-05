package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
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

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage = _errorMessage.asSharedFlow()

    init { viewModelScope.launch(Dispatchers.Main) { setupPlayers() } }

    private fun setupPlayers() {
        // 1. Define Audio Attributes
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // 2. Define Renderers Factory (Same as before)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                pcmEncodingRestrictionLifted: Boolean,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            emptyArray(),
                            SilenceSkippingAudioProcessor(),
                            SonicAudioProcessor()
                        )
                    )
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        if (playerA == null) {
            playerA = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                // CRITICAL FIX: set handleAudioFocus = false
                .setAudioAttributes(audioAttributes, false)
                .setHandleAudioBecomingNoisy(true)
                .build().apply {
                    volume = 1f
                    setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
                }
        }

        if (playerB == null) {
            // Re-create factory for B (Factory instances cannot be shared safely)
            val renderersFactoryB = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink? {
                    return DefaultAudioSink.Builder(context)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .build()
                }
            }.apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }

            playerB = ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactoryB)
                .setAudioAttributes(audioAttributes, false)
                .setHandleAudioBecomingNoisy(true)
                .build().apply {
                    volume = 1f
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
        Log.d("TRANSITION_DEBUG", "1. loadData called for A=$songAId, B=$songBId")

        viewModelScope.launch(Dispatchers.IO) {
            Log.d("TRANSITION_DEBUG", "2. Coroutine started")

            val songA = database.song(songAId).firstOrNull()
            val songB = database.song(songBId).firstOrNull()

            Log.d("TRANSITION_DEBUG", "3. DB Fetch Complete. SongA found: ${songA != null}, SongB found: ${songB != null}")

            if (songA == null || songB == null) {
                Log.e("TRANSITION_DEBUG", "CRITICAL: One or both songs are null. Aborting.")
                return@launch
            }

            _track1.value = songA
            _track2.value = songB
            Log.d("TRANSITION_DEBUG", "4. StateFlows _track1 and _track2 updated")

            // Durations
            val durA = songA?.let { loadExactDuration(it.id) } ?: songA?.song?.duration?.toDouble() ?: 1.0
            val durB = songB?.let { loadExactDuration(it.id) } ?: songB?.song?.duration?.toDouble() ?: 1.0
            Log.d("TRANSITION_DEBUG", "5. Durations loaded. A=$durA, B=$durB")

            // Grid Loading
            val gridPathA = songA?.song?.beatGridPath
            val gridPathB = songB?.song?.beatGridPath
            val loadedGridA = loadBeatGridDouble(gridPathA, songAId)
            val loadedGridB = loadBeatGridDouble(gridPathB, songBId)

            Log.d("TRANSITION_DEBUG", "6. Raw Grids Loaded from disk. A size=${loadedGridA.size}, B size=${loadedGridB.size}")
            Log.d("TRANSITION_DEBUG", "   Path A: $gridPathA exists? ${File(gridPathA ?: "").exists()}")

            // BPMs
            val analysisBpmA = songA?.song?.bpm ?: 120f
            val displayBpmA = songA?.song?.displayBpm ?: 120f
            val analysisBpmB = songB?.song?.bpm ?: 120f
            val displayBpmB = songB?.song?.displayBpm ?: 120f
            Log.d("TRANSITION_DEBUG", "7. BPMs A: $analysisBpmA/$displayBpmA, B: $analysisBpmB/$displayBpmB")

            // 1. Resolve Grids
            Log.d("TRANSITION_DEBUG", "8. Calling BeatGridNormalizer...")
            try {
                val dualA = BeatGridNormalizer.resolveDjGrids(
                    detectedGrid = loadedGridA.map { it.toFloat() },
                    analysisBpm = analysisBpmA,
                    displayBpm = displayBpmA,
                    durationSec = durA.toFloat()
                )
                val dualB = BeatGridNormalizer.resolveDjGrids(
                    detectedGrid = loadedGridB.map { it.toFloat() },
                    analysisBpm = analysisBpmB,
                    displayBpm = displayBpmB,
                    durationSec = durB.toFloat()
                )

                rawGrid1 = dualA.sync.map { it.toDouble() }
                rawGrid2 = dualB.sync.map { it.toDouble() }
                Log.d("TRANSITION_DEBUG", "9. BeatGridNormalizer success. Final Grid Sizes -> A: ${rawGrid1.size}, B: ${rawGrid2.size}")

            } catch (e: Exception) {
                Log.e("TRANSITION_DEBUG", "CRITICAL: BeatGridNormalizer crashed", e)
                return@launch
            }

            // === GRID DIAGNOSTICS ===
            // (Keeping your original debug log here, but adding prefix)
            Log.d("TRANSITION_DEBUG", "GRID_DEBUG: Track A first 5: ${rawGrid1.take(5)}")

            // 2. BPM Matching Logic
            val bpmA = displayBpmA
            val bpmB = displayBpmB
            val bpmDifference = kotlin.math.abs(bpmA - bpmB)

            Log.d("TRANSITION_DEBUG", "10. Calculating Tempo Match. Diff: $bpmDifference")

            if (bpmDifference <= 15f) {
                Log.d("TRANSITION_DEBUG", "    Mode: Tempo Sync")
                initialSpeedB = (bpmA / bpmB).toDouble()
                trackBGridScalar = 1.0
            } else {
                Log.d("TRANSITION_DEBUG", "    Mode: Interval Matching")
                val avgIntervalA = if (rawGrid1.size > 1) (rawGrid1.last() - rawGrid1.first()) / (rawGrid1.size - 1) else 0.5
                val avgIntervalB = if (rawGrid2.size > 1) (rawGrid2.last() - rawGrid2.first()) / (rawGrid2.size - 1) else 0.5
                val rawRatio = if (avgIntervalA > 0 && avgIntervalB > 0) avgIntervalB / avgIntervalA else 1.0

                val candidates = listOf(0.5, 1.0, 1.5, 2.0, 4.0)
                val bestMultiplier = candidates.minByOrNull { k -> kotlin.math.abs(1.0 - (rawRatio / k)) } ?: 1.0

                trackBGridScalar = bestMultiplier
                initialSpeedB = rawRatio / bestMultiplier
            }
            Log.d("TRANSITION_DEBUG", "11. Speed calc done. SpeedB=$initialSpeedB, Scalar=$trackBGridScalar")

            // 4. Waveform Generation
            val samplesPerBeat = 64
            Log.d("TRANSITION_DEBUG", "12. Generating Waveforms...")

            val wfA = loadWaveform(songA?.song?.waveformPath, songAId)
            val wfB = loadWaveform(songB?.song?.waveformPath, songBId)
            Log.d("TRANSITION_DEBUG", "    Raw Waveform sizes: A=${wfA.size}, B=${wfB.size}")

            _waveformBeatDomain1.value = convertWaveformToBeatDomain(
                wfA, rawGrid1, durA, samplesPerBeat, 1.0
            )
            _waveformBeatDomain2.value = convertWaveformToBeatDomain(
                wfB, rawGrid2, durB, samplesPerBeat, trackBGridScalar
            )
            Log.d("TRANSITION_DEBUG", "13. Waveforms converted to Beat Domain. Sizes: ${_waveformBeatDomain1.value.size}, ${_waveformBeatDomain2.value.size}")

            updateTransitionDuration()

            withContext(Dispatchers.Main) {
                Log.d("TRANSITION_DEBUG", "14. Switching to Main Thread to setup players")
                setupPlayers()

                if (songA?.song?.localPath == null) Log.e("TRANSITION_DEBUG", "CRITICAL: Song A Local Path is NULL")
                if (songB?.song?.localPath == null) Log.e("TRANSITION_DEBUG", "CRITICAL: Song B Local Path is NULL")

                songA?.song?.localPath?.let {
                    Log.d("TRANSITION_DEBUG", "    Setting MediaItem A: $it")
                    playerA?.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(it))))
                    playerA?.prepare()
                }
                songB?.song?.localPath?.let {
                    Log.d("TRANSITION_DEBUG", "    Setting MediaItem B: $it")
                    playerB?.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(it))))
                    playerB?.prepare()
                }
                Log.d("TRANSITION_DEBUG", "15. Players Prepared. loadData COMPLETE.")
            }
        }
    }

    // --- Playback Logic ---
    fun togglePlayback() {
        val currentState = _isPlaying.value
        Log.d("PLAYBACK_DEBUG", "togglePlayback() called. Current state: $currentState")
        if (currentState) {
            stopPreview()
        } else {
            startPreview()
        }
    }

    private fun startPreview() {
        Log.d("PLAYBACK_DEBUG", "startPreview() initiated (Muted Preroll + Perfect Sync)")

        val pA = playerA
        val pB = playerB
        if (pA == null || pB == null) {
            _isPlaying.value = false
            return
        }

        val gridA = rawGrid1
        val gridB = rawGrid2
        if (gridA.isEmpty() || gridB.isEmpty()) {
            _isPlaying.value = false
            return
        }

        // Initialize EQ if not already done
        if (eqA == null && pA.audioSessionId != 0) {
            try { eqA = Equalizer(0, pA.audioSessionId).apply { enabled = true } } catch (e: Exception) {}
        }
        if (eqB == null && pB.audioSessionId != 0) {
            try { eqB = Equalizer(0, pB.audioSessionId).apply { enabled = true } } catch (e: Exception) {}
        }

        _isPlaying.value = true
        playbackJob?.cancel()

        // --- Calculate Transition Zone Boundaries ---
        val beatOffsetA = _track1OffsetBeats.value.toDouble()
        val beatOffsetB = _track2OffsetBeats.value.toDouble()
        val zoneFraction = _transitionWidthFraction.value
        val beatsInZone = (_barsCount.value * 4).toDouble()
        val marginFraction = (1f - zoneFraction) / 2f
        val visualMarginBeats = if (zoneFraction > 0f) (marginFraction / zoneFraction) * beatsInZone else 0.0

        // Anchor beat is where the transition zone (green box) starts
        val anchorBeatA = beatOffsetA + visualMarginBeats
        val internalBeatB = if (trackBGridScalar == 1.0) beatOffsetB + visualMarginBeats
        else (beatOffsetB + visualMarginBeats) / trackBGridScalar

        val timeAnchorA = getTimestampForBeat(gridA, anchorBeatA)
        val timeAnchorB = getTimestampForBeat(gridB, internalBeatB)

        // --- Preroll Configuration ---
        val prerollSeconds = 3.0 // Track B starts muted 3 seconds early
        val phaseErrorThreshold = 0.5 // Seek if error exceeds 0.5 beats (in beats)

        // Set initial playback speeds
        pA.setPlaybackSpeed(1.0f)
        val safeSpeedB = if (initialSpeedB.isFinite() && initialSpeedB > 0) initialSpeedB.toFloat() else 1.0f
        pB.setPlaybackSpeed(safeSpeedB)

        // Calculate seek positions
        val seekA = (timeAnchorA - prerollSeconds).coerceAtLeast(0.0)
        val prerollB = if (trackBGridScalar == 1.0) prerollSeconds else prerollSeconds * initialSpeedB
        val seekB = (timeAnchorB - prerollB).coerceAtLeast(0.0)

        // --- Prepare and Start Players ---
        pA.volume = 0f
        pB.volume = 0f // Track B starts muted

        pA.setMediaItem(pA.currentMediaItem ?: return)
        pB.setMediaItem(pB.currentMediaItem ?: return)
        pA.prepare()
        pB.prepare()

        pA.seekTo((seekA * 1000).toLong())
        pB.seekTo((seekB * 1000).toLong())

        pA.play()
        pB.play()

        playbackJob = viewModelScope.launch(Dispatchers.Main) {
            try {
                val transitionDurationBeats = beatsInZone
                val transitionStartBeatA = anchorBeatA // When to unmute track B
                val unmuteBeatA = getBeatForTimestamp(gridA, seekA + 0.5) // When to unmute track A

                val bandsA = eqA?.numberOfBands ?: 0.toShort()
                val bandsB = eqB?.numberOfBands ?: 0.toShort()
                val minEQ = eqA?.bandLevelRange?.get(0) ?: -1500

                // --- Clock Activation State ---
                var isClockAActive = false
                var isClockBActive = false
                var lastPosA = -1.0
                var lastPosB = -1.0
                var clockBStartTime = 0L

                val loopStartTime = System.currentTimeMillis()

                // PLL State
                var currentAppliedSpeed = safeSpeedB
                var hasSeekOccurred = false // Track if we've already sought during preroll

                Log.d("PLAYBACK_DEBUG", "Preroll: B starts at ${seekB}s, transition zone at ${timeAnchorA}s")

                while (isActive && _isPlaying.value) {
                    if (playerA == null || playerB == null) break

                    val posA = pA.currentPosition / 1000.0
                    val posB = pB.currentPosition / 1000.0

                    // --- 1. Watchdog (Stall Detection) ---
                    if (pA.playbackState == androidx.media3.common.Player.STATE_READY && !pA.isPlaying) pA.play()
                    if (pB.playbackState == androidx.media3.common.Player.STATE_READY && !pB.isPlaying) pB.play()

                    // Safety timeout for B
                    if (!isClockBActive && System.currentTimeMillis() - loopStartTime > 1500) {
                        Log.w("PLAYBACK_DEBUG", "B clock stalled > 1.5s. Forcing restart.")
                        pB.pause()
                        pB.play()
                    }

                    // --- 2. Clock Activation Detection ---
                    if (!isClockAActive) {
                        if (posA > lastPosA + 0.001) {
                            isClockAActive = true
                            Log.d("PLAYBACK_DEBUG", "Clock A Activated at ${posA}s")
                        }
                        lastPosA = posA
                    }

                    if (!isClockBActive) {
                        if (posB > lastPosB + 0.001) {
                            isClockBActive = true
                            clockBStartTime = System.currentTimeMillis()
                            Log.d("PLAYBACK_DEBUG", "Clock B Activated at ${posB}s")
                        }
                        lastPosB = posB
                    }

                    // Gate: Wait for both clocks to start
                    if (!isClockAActive || !isClockBActive) {
                        delay(16)
                        continue
                    }

                    // --- 3. Phase Error Calculation ---
                    val currentBeatA = getBeatForTimestamp(gridA, posA)
                    _playbackBeatMarker.value = currentBeatA.toFloat()

                    // Calculate where track B should be in its timeline
                    val elapsedBeatsA = currentBeatA - anchorBeatA
                    val targetBeatB = if (trackBGridScalar == 1.0) internalBeatB + elapsedBeatsA
                    else (beatOffsetB + visualMarginBeats + elapsedBeatsA) / trackBGridScalar
                    val currentBeatB = getBeatForTimestamp(gridB, posB)

                    // Wrapped phase error in beats
                    var phaseErrorBeats = targetBeatB - currentBeatB
                    while (phaseErrorBeats > 0.5) phaseErrorBeats -= 1.0
                    while (phaseErrorBeats < -0.5) phaseErrorBeats += 1.0

                    // --- 4. Muted Preroll & Optional Seek ---
                    val isInPreroll = currentBeatA < transitionStartBeatA

                    if (isInPreroll && !hasSeekOccurred) {
                        // During muted preroll, check if we need an instant seek
                        if (abs(phaseErrorBeats) > phaseErrorThreshold) {
                            Log.d("PLAYBACK_DEBUG", "Muted preroll: Large phase error ${phaseErrorBeats} beats detected. Seeking B.")

                            // Calculate correct timestamp for track B
                            val correctedTimeB = getTimestampForBeat(gridB, targetBeatB)
                            val seekTargetMs = (correctedTimeB * 1000).toLong().coerceAtLeast(0)

                            // Perform instant seek while muted
                            pB.seekTo(seekTargetMs)
                            hasSeekOccurred = true // Only seek once during preroll

                            Log.d("PLAYBACK_DEBUG", "Seeked B to ${correctedTimeB}s (${targetBeatB} beats)")

                            // Reset PLL state after seek
                            currentAppliedSpeed = safeSpeedB
                            pB.setPlaybackSpeed(safeSpeedB)

                            delay(50) // Brief stabilization delay after seek
                            continue
                        }
                    }

                    // --- 5. Mixer Logic (Volume & EQ) ---
                    val progress = if (transitionDurationBeats > 0)
                        ((currentBeatA - transitionStartBeatA) / transitionDurationBeats).toFloat()
                    else 0f

                    var volA = 0f
                    var volB = 0f

                    if (progress < 0f) {
                        // Preroll phase: A audible if past unmute point, B stays muted
                        volA = if (currentBeatA >= unmuteBeatA) 1f else 0f
                        volB = 0f // Track B remains muted during preroll
                        resetEQ(eqA); resetEQ(eqB)
                    } else if (progress <= 1f) {
                        // Unmute at transition: Crossfade begins
                        val stateA = TransitionMixer.getMixState("A", progress, _overlapMode.value, _eqMode.value, _effectMode.value)
                        val stateB = TransitionMixer.getMixState("B", progress, _overlapMode.value, _eqMode.value, _effectMode.value)
                        volA = stateA.volume
                        volB = stateB.volume // Track B unmutes here (at progress = 0)
                        applyDeckStateToEQ(eqA, stateA, minEQ, bandsA, _effectMode.value)
                        applyDeckStateToEQ(eqB, stateB, minEQ, bandsB, _effectMode.value)

                        if (progress == 0f) {
                            Log.d("PLAYBACK_DEBUG", "Unmute at transition zone: Phase error = ${phaseErrorBeats} beats")
                        }
                    } else {
                        // Post-transition: Only B plays
                        volA = 0f
                        volB = 1f
                        resetEQ(eqB)
                    }

                    pA.volume = volA
                    pB.volume = volB

                    // --- 6. Soft PLL Adjustment Loop ---
                    // Continuous speed adjustment runs even during muted preroll

                    // Adaptive gain scheduling based on mix state
                    val mix = volB.coerceIn(0f, 1f)
                    val baseKp = 0.03f + (0.60f - 0.03f) * (1f - mix)
                    val maxAdjust = 0.015f + (0.12f - 0.015f) * (1f - mix)

                    // Warmup boost for first 250ms of active playback
                    val warmupFactor = if (System.currentTimeMillis() - clockBStartTime < 250) 1.4f else 1f

                    // Soft start: Ramp up correction power over first 150ms
                    val clockStabilizationTime = System.currentTimeMillis() - clockBStartTime
                    val clockGain = (clockStabilizationTime / 150f).coerceIn(0f, 1f)

                    val effectiveKp = baseKp * warmupFactor * clockGain

                    // Soft saturation to prevent overcorrection
                    val shapedError = phaseErrorBeats.toFloat() / (1f + abs(phaseErrorBeats.toFloat()) * 4f)

                    // Calculate speed correction
                    val correction = (shapedError * effectiveKp).coerceIn(-maxAdjust, maxAdjust)
                    val newSpeed = (safeSpeedB + correction).coerceIn(0.5f, 2.0f) // Clamped for safety

                    // Apply speed change if significant
                    if (abs(newSpeed - currentAppliedSpeed) > 0.001f) {
                        pB.setPlaybackSpeed(newSpeed)
                        currentAppliedSpeed = newSpeed
                    }

                    delay(33) // ~30 FPS update rate
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e("PLAYBACK_DEBUG", "Playback error", e)
                    _isPlaying.value = false
                }
            }
        }
    }

    private fun stopPreview() {
        Log.d("PLAYBACK_DEBUG", "stopPreview() called")

        // 1. Cancel the playback job FIRST (stop the PLL loop)
        playbackJob?.cancel()
        playbackJob = null

        // 2. Stop playback
        playerA?.pause()
        playerB?.pause()

        // 3. Reset playback speed to nominal values
        playerA?.setPlaybackSpeed(1f)
        playerB?.setPlaybackSpeed(1f)

        // 4. Reset volumes
        playerA?.volume = 1f
        playerB?.volume = 1f

        // 5. Reset EQ completely
        resetEQ(eqA)
        resetEQ(eqB)

        // 6. Clear playback markers
        _playbackBeatMarker.value = null

        // 7. Clear playing flag
        _isPlaying.value = false

        Log.d("PLAYBACK_DEBUG", "Players paused and fully reset")
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