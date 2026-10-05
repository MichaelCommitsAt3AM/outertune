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

    /** Deck warm-up failures, or a reason the editor can't work with these songs. */
    private val _editorError = MutableStateFlow<String?>(null)
    val loadingError = combine(playbackEngine.loadingError, _editorError) { deckError, editorError ->
        editorError ?: deckError
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _config = MutableStateFlow(TransitionConfig())
    val barsCount = _config.map { it.barsCount }.stateIn(viewModelScope, SharingStarted.Lazily, 4)
    val transitionWidthFraction = _config.map { it.widthFraction }.stateIn(viewModelScope, SharingStarted.Lazily, 0.75f)
    val overlapMode = _config.map { it.overlapMode }.stateIn(viewModelScope, SharingStarted.Lazily, "Overlap")
    val eqMode = _config.map { it.eqMode }.stateIn(viewModelScope, SharingStarted.Lazily, "None")
    val effectMode = _config.map { it.effectMode }.stateIn(viewModelScope, SharingStarted.Lazily, "None")
    
    // --- Change Tracking ---
    private data class TransitionState(
        val config: TransitionConfig,
        val offsetA: Double,
        val offsetB: Double
    )

    /** The saved state, to detect unsaved edits. A flow so [hasChanges] updates after a save. */
    private val originalState = MutableStateFlow<TransitionState?>(null)


    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    private val _track1OffsetBeats = MutableStateFlow(0.0)
    val track1OffsetBeats = _track1OffsetBeats.map { it.toFloat() }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    private val _track2OffsetBeats = MutableStateFlow(0.0)
    val track2OffsetBeats = _track2OffsetBeats.map { it.toFloat() }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    private val _hasChanges = combine(_config, _track1OffsetBeats, _track2OffsetBeats, originalState) { config, offA, offB, original ->
        original != null && original != TransitionState(config, offA, offB)
    }
    val hasChanges = _hasChanges.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _pixelsPerBeatBase = MutableStateFlow(48f)
    val pixelsPerBeatBase = _pixelsPerBeatBase.asStateFlow()

    private var currentScreenWidthPx: Float = 0f

    // --- Loading ---

    fun loadData(songAId: String, songBId: String) {
        viewModelScope.launch {
            val artifacts = editorEngine.loadArtifacts(songAId, songBId)
            _editorArtifacts.value = artifacts

            if (artifacts != null) {
                // Check for existing saved transition
                val savedTransition = database.transitionDao().getTransition(songAId, songBId)
                if (savedTransition != null) {
                    // Restore Config
                    updateConfig {
                        it.copy(
                            overlapMode = savedTransition.overlapMode,
                            eqMode = savedTransition.eqMode,
                            effectMode = savedTransition.effectMode,
                            barsCount = (savedTransition.durationBeats ?: 32) / 4
                        )
                    }

                    // Restore the editor position.
                    val savedOffsetA = savedTransition.offsetBeatsA
                    val savedOffsetB = savedTransition.offsetBeatsB
                    if (savedTransition.planVersion != null && savedOffsetA != null && savedOffsetB != null) {
                        // Plan v1+ persists the exact scroll offsets — restore verbatim.
                        _track1OffsetBeats.value = savedOffsetA
                        _track2OffsetBeats.value = savedOffsetB
                    } else {
                        // Legacy rows (pre plan-v1): reverse-engineer the offsets from the saved
                        // exit/entry points. The start of the transition zone (green box) sits
                        // totalBeats * (1 - widthFraction) / (2 * widthFraction) beats right of the offset.
                        val exitBeatA = TransitionMath.getBeatForTimestamp(artifacts.rawGrid1, savedTransition.exitPointMs / 1000.0)
                        val entryBeatB = TransitionMath.getBeatForTimestamp(artifacts.rawGrid2, savedTransition.entryPointMs / 1000.0)
                        val totalBeats = (savedTransition.durationBeats ?: 32).toFloat()
                        val widthFraction = _config.value.widthFraction
                        val startShiftBeats = totalBeats * (1f - widthFraction) / (2f * widthFraction)
                        _track1OffsetBeats.value = exitBeatA - startShiftBeats
                        _track2OffsetBeats.value = entryBeatB - startShiftBeats
                    }
                }

                // Capture Baseline State
                originalState.value = TransitionState(
                    config = _config.value,
                    offsetA = _track1OffsetBeats.value,
                    offsetB = _track2OffsetBeats.value
                )

                val pathA = artifacts.track1.song.localPath
                val pathB = artifacts.track2.song.localPath

                if (pathA != null && pathB != null) {
                    playbackEngine.prewarmDecks(pathA, pathB)
                } else {
                    _editorError.value = "Both songs must be downloaded to preview the transition"
                }

                recalculateZoom()
            } else {
                _editorError.value = "Couldn't load these songs. Make sure both are downloaded and analysed."
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
        _isSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val plan = calculateCurrentPlan()
            val artifacts = _editorArtifacts.value
            if (plan == null || artifacts == null) {
                withContext(Dispatchers.Main) { _isSaving.value = false }
                return@launch
            }

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
                effectMode = _config.value.effectMode,
                // Persist the full synchronisation contract so playback reproduces the preview exactly.
                planVersion = TransitionEntity.PLAN_VERSION,
                initialSpeedB = plan.initialSpeedB,
                gridScalarB = plan.gridScalarB,
                transitionDurationBeats = plan.transitionDurationBeats,
                offsetBeatsA = _track1OffsetBeats.value,
                offsetBeatsB = _track2OffsetBeats.value,
            )

            database.transitionDao().insert(transition)

            withContext(Dispatchers.Main) {
                // Update baseline after successful save
                originalState.value = TransitionState(
                    config = _config.value,
                    offsetA = _track1OffsetBeats.value,
                    offsetB = _track2OffsetBeats.value
                )
                _isSaving.value = false
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
        val bpmA = artifacts.track1.song.displayBpm ?: return null
        val bpmB = artifacts.track2.song.displayBpm ?: return null

        return TransitionMath.calculatePlan(
            gridA = artifacts.rawGrid1,
            gridB = artifacts.rawGrid2,
            bpmA = bpmA,
            bpmB = bpmB,
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