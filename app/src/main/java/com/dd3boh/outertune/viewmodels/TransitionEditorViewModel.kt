package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.TransitionEntity
import com.dd3boh.outertune.transition.editor.EditorArtifacts
import com.dd3boh.outertune.transition.editor.PreviewSession
import com.dd3boh.outertune.transition.editor.TransitionEditorEngine
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.EffectMode
import com.dd3boh.outertune.transition.model.EqMode
import com.dd3boh.outertune.transition.model.OverlapMode
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.utils.LoudnessNormalization
import com.dd3boh.outertune.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TransitionEditorViewModel @Inject constructor(
    private val database: MusicDatabase,
    private val editorEngine: TransitionEditorEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val preview = PreviewSession(context, viewModelScope)

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
    val beatMarkersB = _editorArtifacts.map { it?.beatMarkersB ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isPlaying = preview.state.map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Current beat on Track A while previewing, for the playhead. */
    val playbackBeatMarker = preview.state.map { state -> state.beatA?.toFloat() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val areDecksReady = preview.ready

    /** Preview failures, or a reason the editor can't work with these songs. */
    private val _editorError = MutableStateFlow<String?>(null)
    val loadingError = combine(preview.error, _editorError) { previewError, editorError ->
        editorError ?: previewError
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _config = MutableStateFlow(TransitionConfig())

    /**
     * The last beat of Track A that may sit on the centre line: the zone then ends on A's last
     * beat. Further right, A would end mid-transition (or before a preview reaches the zone).
     */
    val maxCenterBeatA = combine(_editorArtifacts, _config) { artifacts, config ->
        artifacts?.let { (it.rawGrid1.size - 1 - config.barsCount * 2).coerceAtLeast(0).toFloat() }
            ?: Float.POSITIVE_INFINITY
    }.stateIn(viewModelScope, SharingStarted.Lazily, Float.POSITIVE_INFINITY)
    val barsCount = _config.map { it.barsCount }.stateIn(viewModelScope, SharingStarted.Lazily, 4)
    val transitionWidthFraction = _config.map { it.widthFraction }.stateIn(viewModelScope, SharingStarted.Lazily, 0.75f)
    val overlapMode = _config.map { it.overlapMode }.stateIn(viewModelScope, SharingStarted.Lazily, OverlapMode.OVERLAP)
    val eqMode = _config.map { it.eqMode }.stateIn(viewModelScope, SharingStarted.Lazily, EqMode.NONE)
    val effectMode = _config.map { it.effectMode }.stateIn(viewModelScope, SharingStarted.Lazily, EffectMode.NONE)

    // --- Change Tracking ---
    private data class EditorState(
        val config: TransitionConfig,
        val offsetA: Double,
        val offsetB: Double
    )

    /** The saved state, to detect unsaved edits. A flow so [hasChanges] updates after a save. */
    private val originalState = MutableStateFlow<EditorState?>(null)

    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    private val _track1OffsetBeats = MutableStateFlow(0.0)
    val track1OffsetBeats = _track1OffsetBeats.map { it.toFloat() }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    private val _track2OffsetBeats = MutableStateFlow(0.0)
    val track2OffsetBeats = _track2OffsetBeats.map { it.toFloat() }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    val hasChanges = combine(_config, _track1OffsetBeats, _track2OffsetBeats, originalState) { config, offA, offB, original ->
        original != null && original != EditorState(config, offA, offB)
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _pixelsPerBeatBase = MutableStateFlow(48f)
    val pixelsPerBeatBase = _pixelsPerBeatBase.asStateFlow()

    private var currentScreenWidthPx: Float = 0f

    /** Loudness-normalization gains, the same ones playlist playback applies. */
    private var gainA = 1f
    private var gainB = 1f

    // --- Loading ---

    /** The pair already loaded: the screen asks again after a configuration change. */
    private var loadedPair: Pair<String, String>? = null

    fun loadData(songAId: String, songBId: String) {
        // Loading again would restore the saved row over unsaved edits and stop the preview.
        if (loadedPair == songAId to songBId) return
        loadedPair = songAId to songBId
        viewModelScope.launch {
            val artifacts = editorEngine.loadArtifacts(songAId, songBId)
            _editorArtifacts.value = artifacts
            if (artifacts == null) {
                _editorError.value = context.getString(R.string.mix_editor_error_not_analysed)
                return@launch
            }

            database.transitionDao().getTransition(songAId, songBId)?.let { saved -> restore(saved, artifacts) }

            originalState.value = EditorState(_config.value, _track1OffsetBeats.value, _track2OffsetBeats.value)

            gainA = normalizationGain(artifacts.track1)
            gainB = normalizationGain(artifacts.track2)

            val pathA = artifacts.track1.song.localPath
            val pathB = artifacts.track2.song.localPath
            if (pathA != null && pathB != null) {
                preview.load(pathA, pathB)
            } else {
                _editorError.value = context.getString(R.string.mix_editor_error_not_downloaded)
            }

            recalculateZoom()
        }
    }

    private fun restore(saved: TransitionEntity, artifacts: EditorArtifacts) {
        _config.value = _config.value.copy(
            overlapMode = saved.overlapMode,
            eqMode = saved.eqMode,
            effectMode = saved.effectMode,
            barsCount = saved.bars,
        )

        val savedOffsetA = saved.offsetBeatsA
        val savedOffsetB = saved.offsetBeatsB
        if (saved.planVersion != null && savedOffsetA != null && savedOffsetB != null) {
            // Plan v1+ persists the exact scroll offsets — restore verbatim.
            _track1OffsetBeats.value = savedOffsetA
            _track2OffsetBeats.value = savedOffsetB
        } else {
            // Legacy rows: reverse-engineer the offsets from the saved exit/entry points. The start
            // of the transition zone (green box) sits
            // totalBeats * (1 - widthFraction) / (2 * widthFraction) beats right of the offset.
            val exitBeatA = TransitionMath.getBeatForTimestamp(artifacts.rawGrid1, saved.exitPointMs / 1000.0)
            val entryBeatB = TransitionMath.getBeatForTimestamp(artifacts.rawGrid2, saved.entryPointMs / 1000.0)
            val widthFraction = _config.value.widthFraction
            val startShiftBeats = saved.transitionDurationBeats * (1f - widthFraction) / (2f * widthFraction)
            // B's offset is in A's beats; entryBeatB is in B's own (they differ for interval-matched pairs).
            val bpmA = artifacts.track1.song.displayBpm
            val bpmB = artifacts.track2.song.displayBpm
            val scalarB = if (bpmA != null && bpmB != null) {
                TransitionMath.syncParameters(artifacts.rawGrid1, artifacts.rawGrid2, bpmA, bpmB).gridScalar
            } else 1.0
            _track1OffsetBeats.value = exitBeatA - startShiftBeats
            _track2OffsetBeats.value = entryBeatB * scalarB - startShiftBeats
        }
    }

    private suspend fun normalizationGain(song: Song): Float = withContext(Dispatchers.IO) {
        val enabled = context.dataStore.data.first()[AudioNormalizationKey] ?: true
        val format = database.format(song.id).first()
        LoudnessNormalization.factor(enabled, format != null, format?.loudnessDb, song.song.isLocal)
    }

    // --- User Actions ---
    fun setOverlapMode(mode: OverlapMode) { _config.value = _config.value.copy(overlapMode = mode) }
    fun setEqMode(mode: EqMode) { _config.value = _config.value.copy(eqMode = mode) }
    fun setEffectMode(mode: EffectMode) { _config.value = _config.value.copy(effectMode = mode) }

    fun setBarsCount(count: Int) {
        _config.value = _config.value.copy(barsCount = count.coerceAtLeast(1))
        recalculateZoom()
        restartPreviewIfPlaying()
    }

    fun setTrack1Offset(pxOffset: Float, pixelsPerBeat: Float) {
        if (pixelsPerBeat <= 0) return
        _track1OffsetBeats.value = (-pxOffset / pixelsPerBeat).toDouble()
        restartPreviewIfPlaying()
    }

    fun setTrack2Offset(pxOffset: Float, pixelsPerBeat: Float) {
        if (pixelsPerBeat <= 0) return
        _track2OffsetBeats.value = (-pxOffset / pixelsPerBeat).toDouble()
        restartPreviewIfPlaying()
    }

    fun setScreenWidth(widthPx: Float) {
        if (currentScreenWidthPx != widthPx) {
            currentScreenWidthPx = widthPx
            recalculateZoom()
        }
    }

    // --- Playback Control ---

    fun togglePlayback() {
        if (preview.state.value.isPlaying) preview.stop() else startPreview()
    }

    fun stopPreview() = preview.stop()

    private fun startPreview() {
        val plan = calculateCurrentPlan() ?: return
        // Modes are read live, so changing them while the preview plays is heard immediately.
        preview.play(plan, config = { _config.value }, gainA = gainA, gainB = gainB)
    }

    /** The plan is fixed for a run, so moving the zone restarts the preview from its lead-in. */
    private fun restartPreviewIfPlaying() {
        if (preview.state.value.isPlaying) startPreview()
    }

    // --- Save Logic ---
    fun saveTransition(onComplete: () -> Unit) {
        val plan = calculateCurrentPlan()
        val artifacts = _editorArtifacts.value
        if (plan == null || artifacts == null) return
        val config = _config.value
        val offsetA = _track1OffsetBeats.value
        val offsetB = _track2OffsetBeats.value

        _isSaving.value = true
        viewModelScope.launch {
            val transition = TransitionEntity(
                fromSongId = artifacts.track1.id,
                toSongId = artifacts.track2.id,
                exitPointMs = plan.exitPointMs.coerceAtLeast(0),
                entryPointMs = plan.entryPointMs.coerceAtLeast(0),
                transitionDurationBeats = plan.transitionDurationBeats,
                syncTempo = true,
                type = TransitionEntity.TYPE_MANUAL,
                overlapMode = config.overlapMode,
                eqMode = config.eqMode,
                effectMode = config.effectMode,
                // Persist the full synchronisation contract so playback reproduces the preview exactly.
                planVersion = TransitionEntity.PLAN_VERSION,
                initialSpeedB = plan.initialSpeedB,
                gridScalarB = plan.gridScalarB,
                offsetBeatsA = offsetA,
                offsetBeatsB = offsetB,
            )
            withContext(Dispatchers.IO) { database.transitionDao().insert(transition) }

            originalState.value = EditorState(config, offsetA, offsetB)
            _isSaving.value = false
            onComplete()
        }
    }

    // --- Helpers ---

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
        preview.release()
    }
}
