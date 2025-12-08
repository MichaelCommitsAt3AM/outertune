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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TransitionEditorViewModel @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _track1 = MutableStateFlow<Song?>(null)
    val track1 = _track1.asStateFlow()

    private val _track2 = MutableStateFlow<Song?>(null)
    val track2 = _track2.asStateFlow()

    private val _waveformData1 = MutableStateFlow(FloatArray(0))
    val waveformData1 = _waveformData1.asStateFlow()

    private val _waveformData2 = MutableStateFlow(FloatArray(0))
    val waveformData2 = _waveformData2.asStateFlow()

    private val _beatGrid1 = MutableStateFlow<List<Float>>(emptyList())
    val beatGrid1 = _beatGrid1.asStateFlow()

    private val _beatGrid2 = MutableStateFlow<List<Float>>(emptyList())
    val beatGrid2 = _beatGrid2.asStateFlow()

    // NEW: Bars selection (2, 4, 8, 16, 32 bars)
    private val _barsCount = MutableStateFlow(4)
    val barsCount = _barsCount.asStateFlow()

    // NEW: Calculate zoom factor based on bars
    private val _zoomFactor1 = MutableStateFlow(1f)
    val zoomFactor1 = _zoomFactor1.asStateFlow()

    private val _zoomFactor2 = MutableStateFlow(1f)
    val zoomFactor2 = _zoomFactor2.asStateFlow()

    fun loadData(songAId: String, songBId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val songA = database.song(songAId).firstOrNull()
            val songB = database.song(songBId).firstOrNull()

            _track1.value = songA
            _track2.value = songB

            // Load Song A Data
            songA?.let {
                _waveformData1.value = loadWaveform(it.song.waveformPath, it.id)
                _beatGrid1.value = loadBeatGrid(it.song.beatGridPath, it.id)
            }

            // Load Song B Data
            songB?.let {
                _waveformData2.value = loadWaveform(it.song.waveformPath, it.id)
                _beatGrid2.value = loadBeatGrid(it.song.beatGridPath, it.id)
            }

            // Calculate initial zoom
            updateZoomFactors()
        }
    }

    fun setBarsCount(bars: Int) {
        _barsCount.value = bars
        updateZoomFactors()
    }

    private fun updateZoomFactors() {
        val bars = _barsCount.value
        val beatsToShow = bars * 4 // 4 beats per bar in 4/4 time

        android.util.Log.d("TransitionEditorVM", "========================================")
        android.util.Log.d("TransitionEditorVM", "UPDATE ZOOM: bars=$bars, beatsToShow=$beatsToShow")
        android.util.Log.d("TransitionEditorVM", "========================================")

        // Track 1
        val beats1 = _beatGrid1.value
        val duration1 = _track1.value?.song?.duration?.toFloat() ?: 0f

        android.util.Log.d("TransitionEditorVM", "Track 1:")
        android.util.Log.d("TransitionEditorVM", "  Total beats: ${beats1.size}")
        android.util.Log.d("TransitionEditorVM", "  Song duration: ${duration1}s")
        android.util.Log.d("TransitionEditorVM", "  First 20 beats: ${beats1.take(20)}")

        if (beats1.size >= beatsToShow && beats1.isNotEmpty() && duration1 > 0) {
            val firstBeat = beats1.first()
            val lastBeatToShow = beats1.take(beatsToShow).last()
            val timeSpanToShow = lastBeatToShow - firstBeat

            android.util.Log.d("TransitionEditorVM", "  firstBeat: $firstBeat")
            android.util.Log.d("TransitionEditorVM", "  lastBeatToShow (#${beatsToShow}): $lastBeatToShow")
            android.util.Log.d("TransitionEditorVM", "  timeSpanToShow: $timeSpanToShow")

            val zoom = if (timeSpanToShow > 0.1f) {
                duration1 / timeSpanToShow
            } else {
                android.util.Log.e("TransitionEditorVM", "  ERROR: timeSpan too small!")
                1f
            }

            _zoomFactor1.value = zoom
            android.util.Log.d("TransitionEditorVM", "  CALCULATED ZOOM: $zoom")
        } else {
            _zoomFactor1.value = 1f
            android.util.Log.w("TransitionEditorVM", "  Cannot zoom: beats=${beats1.size}, duration=$duration1")
        }

        // Track 2
        val beats2 = _beatGrid2.value
        val duration2 = _track2.value?.song?.duration?.toFloat() ?: 0f

        android.util.Log.d("TransitionEditorVM", "Track 2:")
        android.util.Log.d("TransitionEditorVM", "  Total beats: ${beats2.size}")
        android.util.Log.d("TransitionEditorVM", "  Song duration: ${duration2}s")
        android.util.Log.d("TransitionEditorVM", "  First 20 beats: ${beats2.take(20)}")

        if (beats2.size >= beatsToShow && beats2.isNotEmpty() && duration2 > 0) {
            val firstBeat = beats2.first()
            val lastBeatToShow = beats2.take(beatsToShow).last()
            val timeSpanToShow = lastBeatToShow - firstBeat

            android.util.Log.d("TransitionEditorVM", "  firstBeat: $firstBeat")
            android.util.Log.d("TransitionEditorVM", "  lastBeatToShow (#${beatsToShow}): $lastBeatToShow")
            android.util.Log.d("TransitionEditorVM", "  timeSpanToShow: $timeSpanToShow")

            val zoom = if (timeSpanToShow > 0.1f) {
                duration2 / timeSpanToShow
            } else {
                android.util.Log.e("TransitionEditorVM", "  ERROR: timeSpan too small!")
                1f
            }

            _zoomFactor2.value = zoom
            android.util.Log.d("TransitionEditorVM", "  CALCULATED ZOOM: $zoom")
        } else {
            _zoomFactor2.value = 1f
            android.util.Log.w("TransitionEditorVM", "  Cannot zoom: beats=${beats2.size}, duration=$duration2")
        }

        android.util.Log.d("TransitionEditorVM", "========================================")
    }



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
                val beats = file.readText().split(",").mapNotNull { it.toFloatOrNull() }

                // Convert milliseconds to seconds, Aubio returns beat times in milliseconds
                val beatsInSeconds = beats.map { it / 1000f }

                android.util.Log.d("TransitionEditorVM", "Loaded beats: ${beats.size} beats")
                android.util.Log.d("TransitionEditorVM", "  Raw (ms): ${beats.take(5)}")
                android.util.Log.d("TransitionEditorVM", "  Converted (s): ${beatsInSeconds.take(5)}")

                beatsInSeconds
            } catch (e: Exception) {
                android.util.Log.e("TransitionEditorVM", "Error loading beat grid", e)
                emptyList()
            }
        } else {
            android.util.Log.w("TransitionEditorVM", "Beat grid file not found: ${file.absolutePath}")
            emptyList()
        }
    }

}
