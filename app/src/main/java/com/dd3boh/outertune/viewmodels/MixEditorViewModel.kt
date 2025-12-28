package com.dd3boh.outertune.viewmodels

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.TransitionEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class MixEditorViewModel @Inject constructor(
    private val application: Application,
    private val database: MusicDatabase
) : AndroidViewModel(application) {

    // STATE
    var songA: MediaMetadata? by mutableStateOf(null)
    var songB: MediaMetadata? by mutableStateOf(null)

    // Waveforms (List of amplitudes 0..100)
    var waveformA: List<Int> by mutableStateOf(emptyList())
    var waveformB: List<Int> by mutableStateOf(emptyList())

    // Transition Settings (What the user is editing)
    var exitPointMs by mutableStateOf(0L)
    var entryPointMs by mutableStateOf(0L)
    var durationBeats by mutableStateOf(8) // Default 8 beats

    // Beat Grid Data (Interval in ms)
    var beatIntervalA: Float = 0f
    var beatIntervalB: Float = 0f

    fun loadData(songAId: String, songBId: String) {
        viewModelScope.launch {
            // 1. Fetch Songs & Transition from DB
            val sA = database.song(songAId).firstOrNull()?.toMediaMetadata()
            val sB = database.song(songBId).firstOrNull()?.toMediaMetadata()
            val transition = database.transitionDao().getTransition(songAId, songBId)

            songA = sA
            songB = sB

            // 2. Load Waveforms from Cache
            if (sA?.waveformPath != null) waveformA = loadWaveformFile(sA.waveformPath)
            if (sB?.waveformPath != null) waveformB = loadWaveformFile(sB.waveformPath)

            // 3. Setup Defaults or Load Saved State
            if (transition != null) {
                exitPointMs = transition.exitPointMs
                entryPointMs = transition.entryPointMs
                durationBeats = transition.durationBeats ?: 8
            } else {
                // Default: Exit 10s from end, Entry at 0s
                exitPointMs = (sA?.duration ?: 0) * 1000L - 10000
                entryPointMs = 0L
            }

            // 4. Calculate Beat Intervals (60000 / BPM)
            if ((sA?.bpm ?: 0f) > 0) beatIntervalA = 60000f / sA!!.bpm!!
            if ((sB?.bpm ?: 0f) > 0) beatIntervalB = 60000f / sB!!.bpm!!
        }
    }

    private suspend fun loadWaveformFile(path: String): List<Int> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists()) {
                // We saved it as a comma-separated string in Phase 4
                file.readText().split(",").mapNotNull { it.toIntOrNull() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            reportException(e)
            emptyList()
        }
    }

    fun saveTransition() {
        val sA = songA ?: return
        val sB = songB ?: return

        val bpm = sA.bpm ?: 120f
        val durationMs = (60000f / bpm * durationBeats).toLong()

        val entity = TransitionEntity(
            fromSongId = sA.id,
            toSongId = sB.id,
            exitPointMs = exitPointMs,
            entryPointMs = entryPointMs,
            durationMs = durationMs,
            durationBeats = durationBeats,
            syncTempo = true // Defaulting to true for PoC
        )

        viewModelScope.launch {
            database.transitionDao().insert(entity)
        }
    }
}