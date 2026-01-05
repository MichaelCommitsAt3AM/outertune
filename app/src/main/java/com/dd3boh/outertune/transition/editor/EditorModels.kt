package com.dd3boh.outertune.transition.editor

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.ui.component.BeatGridMarker

/**
 * Data class for the visual waveform.
 * Moved from TransitionEditorViewModel to ensure separation.
 */
data class BeatSample(
    val beatIndex: Float,
    val amplitude: Float
)

/**
 * A container for all the heavy data required *only* by the Editor UI.
 *
 * The PlaybackEngine never sees this.
 * The Playlist Logic never sees this.
 */
data class EditorArtifacts(
    // The Songs (Database Entities)
    val track1: Song,
    val track2: Song,

    // Visual Waveforms (Beat Domain)
    val waveformBeatDomain1: List<BeatSample>,
    val waveformBeatDomain2: List<BeatSample>,

    // UI Markers (Beat Grid lines for the UI)
    val beatMarkers: List<BeatGridMarker>,

    // Raw Grids (Double Precision) - Passed here to allow TransitionMath to use them later
    val rawGrid1: List<Double>,
    val rawGrid2: List<Double>,

    // Raw Durations (Seconds) - Useful for boundary checks
    val durationSec1: Double,
    val durationSec2: Double
)