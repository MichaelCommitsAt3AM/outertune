package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val waveformData1 = _waveformData1.asStateFlow() // Keep for legacy if needed
    val waveformData2 = _waveformData2.asStateFlow()

    private val _beatGrid1 = MutableStateFlow<List<Float>>(emptyList())
    val beatGrid1 = _beatGrid1.asStateFlow()
    private val _beatGrid2 = MutableStateFlow<List<Float>>(emptyList())

    // --- Beat-domain state flows ---
    private val _waveformBeatDomain1 = MutableStateFlow(FloatArray(0))
    val waveformBeatDomain1 = _waveformBeatDomain1.asStateFlow()

    private val _waveformBeatDomain2 = MutableStateFlow(FloatArray(0))
    val waveformBeatDomain2 = _waveformBeatDomain2.asStateFlow()

    // Replaces "ZoomFactor"
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

    // --- Helpers ---

    /**
     * Estimate a stable BPM from a beat grid (uses 16 beats to measure).
     * grid is in seconds.
     */


    /**
     * Convert a time-domain waveform array into a beat-domain waveform.
     *
     * - waveform: amplitude array for the whole track (length = some resolution)
     * - beatGrid: beat timestamps in seconds for that track (size = beatCount)
     * - songDurationSeconds: duration of the track in seconds
     * - pixelsPerBeat: number of visual pixels to allocate for each beat (uniform)
     *
     * Returns a FloatArray whose length ≈ (beatCount-1) * pixelsPerBeat.
     * Each element corresponds to one visual pixel sample in beat space.
     */


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

            // compute stable BPMs
            val bpm1 = calculateStableBpm(_beatGrid1.value, songA?.song?.bpm)
            val bpm2 = calculateStableBpm(_beatGrid2.value, songB?.song?.bpm)

            // set playback speed for deck B so audio would match BPM of A
            val bpmDiff = abs(bpm1 - bpm2)
            if (bpmDiff <= 20f && bpm2 > 0f) {
                _playbackSpeed1.value = 1f
                val speedForB = bpm1 / bpm2
                _playbackSpeed2.value = speedForB
            } else {
                _playbackSpeed1.value = 1f
                _playbackSpeed2.value = 1f
            }

            // compute beat offset (in beats) between the first detected beat of both tracks
            // Using master BPM (bpm1) to express offset in beats.
            val firstA = _beatGrid1.value.firstOrNull() ?: 0f
            val firstB = _beatGrid2.value.firstOrNull() ?: 0f
            if (bpm1 > 0f) {
                val secondsPerBeatA = 60f / bpm1
                val beatOffset = (firstA - firstB) / secondsPerBeatA
                _beatOffsetForTrack2.value = beatOffset
            } else {
                _beatOffsetForTrack2.value = 0f
            }

            // convert both waveforms to beat-domain using the same pixelsPerBeat
            // (we use original beat timings for each track to slice its waveform, but
            // each beat is resampled to the SAME number of pixels)
            val pxPerBeat = _pixelsPerBeatBase.value.toInt().coerceAtLeast(4)

            val durationA = songA?.song?.duration?.toFloat() ?: 1f
            val durationB = songB?.song?.duration?.toFloat() ?: 1f

            val beatDomain1 = convertWaveformToBeatDomain(_waveformData1.value, _beatGrid1.value, durationA, pxPerBeat)
            val beatDomain2 = convertWaveformToBeatDomain(_waveformData2.value, _beatGrid2.value, durationB, pxPerBeat)

            _waveformBeatDomain1.value = beatDomain1
            _waveformBeatDomain2.value = beatDomain2

            // transition duration remains based on master BPM/time-span as before
            val bars = _barsCount.value
            val beatsToShow = bars * 4
            val timeSpanA = beatsToShow * (60f / (if (bpm1 > 0f) bpm1 else 120f))
            _transitionDurationSeconds.value = timeSpanA

            // done
        }
    }

    fun setBarsCount(bars: Int) {
        _barsCount.value = bars
        // Recompute transition duration (UI only)
        val bpm1 = calculateStableBpm(_beatGrid1.value, _track1.value?.song?.bpm)
        val beatsToShow = bars * 4
        if (bpm1 > 0f) {
            _transitionDurationSeconds.value = beatsToShow * (60f / bpm1)
        }
    }

    fun setPixelsPerBeat(pixels: Float) {
        _pixelsPerBeatBase.value = pixels.coerceAtLeast(4f)
        // When pixelsPerBeat changes we should re-run conversion (simple approach: reload)
        // Trigger reconversion on the next load or call loadData again externally.
        // If you want instant reconversion, call convertWaveformToBeatDomain here similarly.
    }

    // --- File loaders (unchanged) ---
    private fun loadWaveform(savedPath: String?, songId: String): FloatArray {
        val file = if (savedPath != null) File(savedPath) else File(context.cacheDir, "analysis_data/${songId}_waveform.dat")
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
        val file = if (savedPath != null) File(savedPath) else File(context.cacheDir, "analysis_data/${songId}_beats.dat")
        return if (file.exists()) {
            try {
                // files store milliseconds in your original code -> convert to seconds
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

    private fun convertWaveformToBeatDomain(
        waveform: FloatArray,
        beatGrid: List<Float>,
        songDurationSeconds: Float,
        pixelsPerBeat: Int
    ): FloatArray {
        if (waveform.isEmpty() || beatGrid.size < 2 || songDurationSeconds <= 0f) return FloatArray(0)

        val out = ArrayList<Float>( (beatGrid.size - 1) * pixelsPerBeat )

        val waveformSize = waveform.size
        for (i in 0 until beatGrid.size - 1) {
            val startTime = beatGrid[i].coerceAtLeast(0f)
            val endTime = beatGrid[i + 1].coerceAtMost(songDurationSeconds)

            val startIndex = ((startTime / songDurationSeconds) * waveformSize).toInt().coerceIn(0, waveformSize - 1)
            val endIndex = ((endTime / songDurationSeconds) * waveformSize).toInt().coerceIn(0, waveformSize)

            val segmentLength = (endIndex - startIndex).coerceAtLeast(1)

            // Simple nearest sampling for speed (you can improve with linear interpolation)
            for (px in 0 until pixelsPerBeat) {
                val t = px.toFloat() / pixelsPerBeat.toFloat()
                val sampleIndex = startIndex + (t * segmentLength).toInt().coerceIn(0, segmentLength - 1)
                out.add(waveform[sampleIndex])
            }
        }

        return out.toFloatArray()
    }
}
