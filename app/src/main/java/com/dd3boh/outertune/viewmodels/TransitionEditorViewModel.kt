package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * Data class to store waveform samples with their beat positions
 */
data class BeatSample(
    val beatIndex: Float,  // Position in beats (0.0, 0.25, 0.5, etc.)
    val amplitude: Float   // Amplitude value (0.0 to 1.0)
)

@HiltViewModel
class TransitionEditorViewModel @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // --- Original state flows ---
    private val _track1 = MutableStateFlow<Song?>(null)
    val track1 = _track1.asStateFlow()

    private val _track2 = MutableStateFlow<Song?>(null)
    val track2 = _track2.asStateFlow()

    private val _waveformData1 = MutableStateFlow(FloatArray(0))
    private val _waveformData2 = MutableStateFlow(FloatArray(0))

    // Keep for legacy if needed
    val waveformData1 = _waveformData1.asStateFlow()
    val waveformData2 = _waveformData2.asStateFlow()

    private val _beatGrid1 = MutableStateFlow<List<Float>>(emptyList())
    val beatGrid1 = _beatGrid1.asStateFlow()
    private val _beatGrid2 = MutableStateFlow<List<Float>>(emptyList())

    // --- Beat-domain state flows (FIXED) ---
    private val _waveformBeatDomain1 = MutableStateFlow<List<BeatSample>>(emptyList())
    val waveformBeatDomain1 = _waveformBeatDomain1.asStateFlow()

    private val _waveformBeatDomain2 = MutableStateFlow<List<BeatSample>>(emptyList())
    val waveformBeatDomain2 = _waveformBeatDomain2.asStateFlow()

    // Base resolution for waveform generation (can be fixed now)
    private val _pixelsPerBeatBase = MutableStateFlow(48f)
    val pixelsPerBeatBase = _pixelsPerBeatBase.asStateFlow()

    private val _barsCount = MutableStateFlow(4)
    val barsCount = _barsCount.asStateFlow()

    private val _playbackSpeed1 = MutableStateFlow(1f)
    val playbackSpeed1 = _playbackSpeed1.asStateFlow()

    private val _playbackSpeed2 = MutableStateFlow(1f)
    val playbackSpeed2 = _playbackSpeed2.asStateFlow()

    private val _beatOffsetForTrack2 = MutableStateFlow(0f)
    val beatOffsetForTrack2 = _beatOffsetForTrack2.asStateFlow()

    private val _transitionDurationSeconds = MutableStateFlow(0f)
    val transitionDurationSeconds = _transitionDurationSeconds.asStateFlow()

    private val _transitionWidthFraction = MutableStateFlow(0.75f)
    val transitionWidthFraction = _transitionWidthFraction.asStateFlow()

    val beatGridIndices = combine(_beatGrid1, _beatGrid2) { b1, b2 ->
        val maxBeats = maxOf(b1.size, b2.size).coerceAtLeast(100)
        List(maxBeats) { it.toFloat() }
    }

    // INTERNAL: Store screen width to calculate zoom
    private var currentScreenWidthPx: Float = 0f
    private var regenerationJob: Job? = null

    fun setScreenWidth(widthPx: Float) {
        if (currentScreenWidthPx != widthPx) {
            currentScreenWidthPx = widthPx
            recalculateZoom()
        }
    }

    // NOTE: We no longer need to regenerate waveforms when zoom changes!
    // The beat-indexed waveform can be rendered at any zoom level.
    private fun recalculateZoom() {
        if (currentScreenWidthPx <= 0) return

        val bars = _barsCount.value
        val transitionFraction = _transitionWidthFraction.value

        // Calculate width of the green zone in pixels
        val zoneWidthPx = currentScreenWidthPx * transitionFraction

        // Calculate how many beats need to fit in that zone
        val totalBeatsToDisplay = bars * 4

        // Calculate pixels per beat for rendering
        val newPixelsPerBeat = zoneWidthPx / totalBeatsToDisplay

        // Update the rendering pixel scale (but don't regenerate waveforms)
        _pixelsPerBeatBase.value = newPixelsPerBeat.coerceAtLeast(4f)

        updateTransitionDuration()
    }

    // --- Public actions ---

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

            // Compute stable BPMs
            val bpm1 = calculateStableBpm(_beatGrid1.value, songA?.song?.bpm)
            val bpm2 = calculateStableBpm(_beatGrid2.value, songB?.song?.bpm)

            // Set playback speed for deck B so audio would match BPM of A
            val bpmDiff = abs(bpm1 - bpm2)
            if (bpmDiff <= 20f && bpm2 > 0f) {
                _playbackSpeed1.value = 1f
                val speedForB = bpm1 / bpm2
                _playbackSpeed2.value = speedForB
            } else {
                _playbackSpeed1.value = 1f
                _playbackSpeed2.value = 1f
            }

            // Compute beat offset (in beats) between the first detected beat of both tracks
            val firstA = _beatGrid1.value.firstOrNull() ?: 0f
            val firstB = _beatGrid2.value.firstOrNull() ?: 0f
            if (bpm1 > 0f) {
                val secondsPerBeatA = 60f / bpm1
                val beatOffset = (firstA - firstB) / secondsPerBeatA
                _beatOffsetForTrack2.value = beatOffset
            } else {
                _beatOffsetForTrack2.value = 0f
            }

            // Convert both waveforms to beat-domain
            // Use a fixed resolution (48 pixels per beat) for generation
            val fixedPxPerBeat = 48

            val durationA = songA?.song?.duration?.toFloat() ?: 1f
            val durationB = songB?.song?.duration?.toFloat() ?: 1f

            val beatDomain1 = convertWaveformToBeatDomain(
                _waveformData1.value,
                _beatGrid1.value,
                durationA,
                fixedPxPerBeat
            )
            val beatDomain2 = convertWaveformToBeatDomain(
                _waveformData2.value,
                _beatGrid2.value,
                durationB,
                fixedPxPerBeat
            )

            _waveformBeatDomain1.value = beatDomain1
            _waveformBeatDomain2.value = beatDomain2

            // Transition duration based on master BPM
            val bars = _barsCount.value
            val beatsToShow = bars * 4
            val timeSpanA = beatsToShow * (60f / (if (bpm1 > 0f) bpm1 else 120f))
            _transitionDurationSeconds.value = timeSpanA
        }
    }

    fun setBarsCount(newBars: Int) {
        _barsCount.value = newBars       // FIX
        _beatOffsetForTrack2.value = 0f  // FIX
        updateTransitionDuration()       // Also needed so duration updates immediately
    }


    // Separated this logic so we can call it without re-loading files
    private fun updateTransitionDuration() {
        val bpm1 = calculateStableBpm(_beatGrid1.value, _track1.value?.song?.bpm)
        val beatsToShow = _barsCount.value * 4
        if (bpm1 > 0f) {
            _transitionDurationSeconds.value = beatsToShow * (60f / bpm1)
        }
    }

    // --- File loaders ---
    private fun loadWaveform(savedPath: String?, songId: String): FloatArray {
        val file = if (savedPath != null) File(savedPath)
        else File(context.cacheDir, "analysis_data/${songId}_waveform.dat")
        return if (file.exists()) {
            try {
                file.readText().split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            } catch (e: Exception) {
                FloatArray(0)
            }
        } else {
            FloatArray(0)
        }
    }

    private fun loadBeatGrid(savedPath: String?, songId: String): List<Float> {
        val file = if (savedPath != null) File(savedPath)
        else File(context.cacheDir, "analysis_data/${songId}_beats.dat")
        return if (file.exists()) {
            try {
                // Files store milliseconds -> convert to seconds
                file.readText().split(",").mapNotNull { it.toFloatOrNull() }.map { it / 1000f }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    private fun calculateStableBpm(grid: List<Float>, metadataBpm: Float?): Float {
        val beatsToMeasure = 16
        if (grid.size > beatsToMeasure) {
            val duration = grid[beatsToMeasure] - grid[0]
            if (duration > 0f) {
                return (beatsToMeasure * 60f) / duration
            }
        }
        return metadataBpm ?: 120f
    }

    /**
     * FIXED: Convert a time-domain waveform array into a beat-indexed waveform.
     *
     * Returns a List<BeatSample> where each sample stores:
     * - beatIndex: its position in beats (e.g., 0.0, 0.25, 0.5, 1.0, etc.)
     * - amplitude: the peak amplitude value
     *
     * This structure allows rendering at any pixelsPerBeat value without regeneration.
     */
    private fun convertWaveformToBeatDomain(
        waveform: FloatArray,
        beatGrid: List<Float>,
        songDurationSeconds: Float,
        pixelsPerBeat: Int
    ): List<BeatSample> {
        if (waveform.isEmpty() || beatGrid.size < 2 || songDurationSeconds <= 0f) {
            return emptyList()
        }

        val out = ArrayList<BeatSample>((beatGrid.size - 1) * pixelsPerBeat)
        val waveformSize = waveform.size

        for (beatIdx in 0 until beatGrid.size - 1) {
            val startTime = beatGrid[beatIdx].coerceAtLeast(0f)
            val endTime = beatGrid[beatIdx + 1].coerceAtMost(songDurationSeconds)

            // Map time to indices in the raw waveform array
            val startIndex = ((startTime / songDurationSeconds) * waveformSize).toInt()
                .coerceIn(0, waveformSize - 1)
            val endIndex = ((endTime / songDurationSeconds) * waveformSize).toInt()
                .coerceIn(0, waveformSize)

            // How many raw samples represent this ONE beat?
            val segmentLength = (endIndex - startIndex).coerceAtLeast(1)

            for (px in 0 until pixelsPerBeat) {
                // Calculate which raw samples cover this specific pixel
                val pixelStartPct = px.toFloat() / pixelsPerBeat
                val pixelEndPct = (px + 1).toFloat() / pixelsPerBeat

                val rawStartOffset = (pixelStartPct * segmentLength).toInt()
                val rawEndOffset = (pixelEndPct * segmentLength).toInt()

                val searchStart = (startIndex + rawStartOffset).coerceIn(0, waveformSize - 1)
                val searchEnd = (startIndex + rawEndOffset).coerceIn(searchStart, waveformSize)

                // PEAK DETECTION: Find the loudest sample in this range
                var maxAmp = 0f
                if (searchStart == searchEnd) {
                    maxAmp = abs(waveform[searchStart])
                } else {
                    for (k in searchStart until searchEnd) {
                        val amp = abs(waveform[k])
                        if (amp > maxAmp) maxAmp = amp
                    }
                }

                // FIXED: Store beat position instead of relying on array index
                val beatPosition = beatIdx + (px.toFloat() / pixelsPerBeat)
                out.add(BeatSample(beatPosition, maxAmp))
            }
        }

        return out
    }
}