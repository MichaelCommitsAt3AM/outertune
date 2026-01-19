package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.TransitionEntity
import com.dd3boh.outertune.transition.editor.EditorArtifacts
import com.dd3boh.outertune.transition.editor.TransitionEditorEngine
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.transition.playback.TransitionPlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TransitionEditorViewModel @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val editorEngine = TransitionEditorEngine(context, database)
    private val playbackEngine = TransitionPlaybackEngine(context)
    
    init {
        // Monitor state changes for debugging
        viewModelScope.launch {
            playbackEngine.decksReady.collect { ready ->
                android.util.Log.d(TAG, "playbackEngine.decksReady changed to: $ready")
            }
        }
        viewModelScope.launch {
            playbackEngine.loadingError.collect { error ->
                android.util.Log.d(TAG, "playbackEngine.loadingError changed to: $error")
            }
        }
    }

    // --- UI State ---
    private val _editorArtifacts = MutableStateFlow<EditorArtifacts?>(null)
    val track1 = _editorArtifacts.map { it?.track1 }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val track2 = _editorArtifacts.map { it?.track2 }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val waveformBeatDomain1 = _editorArtifacts.map { it?.waveformBeatDomain1 ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val waveformBeatDomain2 = _editorArtifacts.map { it?.waveformBeatDomain2 ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val beatMarkers = _editorArtifacts.map { it?.beatMarkers ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isPlaying = playbackEngine.playbackState.map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val playbackBeatMarker = playbackEngine.playbackState.map { state ->
        if (state.isPlaying) state.currentBeatA else null
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Expose Decks Ready state to UI (optional, can be used to disable Play button)
    val areDecksReady = playbackEngine.decksReady
        .onEach { android.util.Log.d(TAG, "areDecksReady flow emitting: $it") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Expose loading error
    val loadingError = playbackEngine.loadingError
        .onEach { android.util.Log.d(TAG, "loadingError flow emitting: $it") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _config = MutableStateFlow(TransitionConfig())
    val barsCount = _config.map { it.barsCount }.stateIn(viewModelScope, SharingStarted.Lazily, 4)
    val transitionWidthFraction = _config.map { it.widthFraction }.stateIn(viewModelScope, SharingStarted.Lazily, 0.75f)
    val overlapMode = _config.map { it.overlapMode }.stateIn(viewModelScope, SharingStarted.Lazily, "Overlap")
    val eqMode = _config.map { it.eqMode }.stateIn(viewModelScope, SharingStarted.Lazily, "None")
    val effectMode = _config.map { it.effectMode }.stateIn(viewModelScope, SharingStarted.Lazily, "None")

    private val _track1OffsetBeats = MutableStateFlow(0.0)
    val track1OffsetBeats = _track1OffsetBeats.map { it.toFloat() }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    private val _track2OffsetBeats = MutableStateFlow(0.0)
    val track2OffsetBeats = _track2OffsetBeats.map { it.toFloat() }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    private val _pixelsPerBeatBase = MutableStateFlow(48f)
    val pixelsPerBeatBase = _pixelsPerBeatBase.asStateFlow()

    private var currentScreenWidthPx: Float = 0f

    // --- Loading ---

    fun loadData(songAId: String, songBId: String) {
        viewModelScope.launch {
            val artifacts = editorEngine.loadArtifacts(songAId, songBId)
            _editorArtifacts.value = artifacts

            if (artifacts != null) {
                val pathA = artifacts.track1.song.localPath
                val pathB = artifacts.track2.song.localPath

                if (pathA != null && pathB != null) {
                    android.util.Log.d("TransitionEditorViewModel", "Calling prewarmDecks with paths: A=$pathA, B=$pathB")
                    playbackEngine.prewarmDecks(pathA, pathB)
                } else {
                    android.util.Log.e("TransitionEditorViewModel", "Cannot prewarm decks - paths are null: A=$pathA, B=$pathB")
                }

                recalculateZoom()
            } else {
                android.util.Log.e("TransitionEditorViewModel", "loadArtifacts returned null")
            }
        }
    }

    // --- User Actions ---
    fun setOverlapMode(mode: String) { updateConfig { it.copy(overlapMode = mode) } }
    fun setEqMode(mode: String) { updateConfig { it.copy(eqMode = mode) } }
    fun setEffectMode(mode: String) { updateConfig { it.copy(effectMode = mode) } }

    fun setBarsCount(count: Int) {
        updateConfig { it.copy(barsCount = count.coerceAtLeast(1)) }
        recalculateZoom()
    }

    fun setTrack1Offset(pxOffset: Float, pixelsPerBeat: Float) {
        if (pixelsPerBeat > 0) _track1OffsetBeats.value = (-pxOffset / pixelsPerBeat).toDouble()
    }

    fun setTrack2Offset(pxOffset: Float, pixelsPerBeat: Float) {
        if (pixelsPerBeat > 0) _track2OffsetBeats.value = (-pxOffset / pixelsPerBeat).toDouble()
    }

    fun setScreenWidth(widthPx: Float) {
        if (currentScreenWidthPx != widthPx) {
            currentScreenWidthPx = widthPx
            recalculateZoom()
        }
    }

    // --- Playback Control ---

    fun togglePlayback() {
        if (isPlaying.value) {
            playbackEngine.stop()
        } else {
            // Guard: Ensure decks are hot (Recommendation 1)
            if (!playbackEngine.decksReady.value) return

            val plan = calculateCurrentPlan() ?: return
            playbackEngine.play(plan, _config.value)
        }
    }

    // --- Save Logic ---
    fun saveTransition(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val plan = calculateCurrentPlan() ?: return@launch
            val artifacts = _editorArtifacts.value ?: return@launch

            val transition = TransitionEntity(
                fromSongId = artifacts.track1.id,
                toSongId = artifacts.track2.id,
                exitPointMs = plan.exitPointMs.coerceAtLeast(0),
                entryPointMs = plan.entryPointMs.coerceAtLeast(0),
                durationMs = plan.durationMs,
                durationBeats = _config.value.barsCount * 4,
                syncTempo = true,
                type = TransitionEntity.TYPE_MANUAL,
                overlapMode = _config.value.overlapMode,
                eqMode = _config.value.eqMode,
                effectMode = _config.value.effectMode
            )

            database.transitionDao().insert(transition)

            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    // --- Helpers ---

    private fun updateConfig(update: (TransitionConfig) -> TransitionConfig) {
        val newConfig = update(_config.value)
        _config.value = newConfig
        if (isPlaying.value) {
            playbackEngine.updateConfig(newConfig)
        }
    }

    private fun recalculateZoom() {
        if (currentScreenWidthPx <= 0f) return
        val zoneWidth = currentScreenWidthPx * _config.value.widthFraction
        val totalBeats = _config.value.barsCount * 4
        _pixelsPerBeatBase.value = (zoneWidth / totalBeats).coerceAtLeast(4f)
    }

    private fun calculateCurrentPlan(): TransitionPlan? {
        val artifacts = _editorArtifacts.value ?: return null

        return TransitionMath.calculatePlan(
            gridA = artifacts.rawGrid1,
            gridB = artifacts.rawGrid2,
            bpmA = artifacts.track1.song.displayBpm!!,
            bpmB = artifacts.track2.song.displayBpm!!,
            offsetBeatsA = _track1OffsetBeats.value,
            offsetBeatsB = _track2OffsetBeats.value,
            config = _config.value
        )
    }

    override fun onCleared() {
        super.onCleared()
        playbackEngine.release()
    }
    
    companion object {
        private const val TAG = "TransitionEditorViewModel"
    }
}