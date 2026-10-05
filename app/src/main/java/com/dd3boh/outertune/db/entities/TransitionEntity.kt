package com.dd3boh.outertune.db.entities

import androidx.room.Entity

@Entity(
    tableName = "transitions",
    primaryKeys = ["fromSongId", "toSongId"]
)
data class TransitionEntity(
    val fromSongId: String,
    val toSongId: String,

    // The point in Song A (outgoing) where the crossfade starts
    val exitPointMs: Long,

    // The point in Song B (incoming) where playback begins (aligns with start of crossfade)
    val entryPointMs: Long,

    // How long the transition lasts
    val durationMs: Long = 8000,
    val durationBeats: Int? = null,

    val syncTempo: Boolean = true,
    val type: Int = TYPE_MANUAL,

    // --- Effect storage ---
    val overlapMode: String = "Overlap", // Overlap, Crossfade, Cut
    val eqMode: String = "None",         // Bass Swaps etc
    val effectMode: String = "None",     // Filters etc

    // --- Persisted TransitionPlan (the editor's synchronisation contract) ---
    // When planVersion != null the playback engine trusts these values verbatim instead of
    // re-deriving them from BPM. See TransitionMath.calculatePlan / TransitionEditorViewModel.
    val planVersion: Int? = null,

    /** Playback speed multiplier applied to Track B (e.g. 1.02 = 2% faster). */
    val initialSpeedB: Double? = null,

    /** Grid compression factor for interval-matched transitions (1.0 = strict tempo match). */
    val gridScalarB: Double? = null,

    /** Transition length in beats (barsCount * 4). */
    val transitionDurationBeats: Double? = null,

    /** Editor waveform scroll offsets (beats) — used only to restore the editor UI exactly. */
    val offsetBeatsA: Double? = null,
    val offsetBeatsB: Double? = null,
) {
    companion object {
        const val TYPE_MANUAL = 0
        const val TYPE_AUTO = 1

        /** Bump when the persisted plan fields change meaning. */
        const val PLAN_VERSION = 1
    }
}
