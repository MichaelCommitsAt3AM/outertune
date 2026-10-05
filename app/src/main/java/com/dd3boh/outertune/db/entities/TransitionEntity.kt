package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import com.dd3boh.outertune.transition.model.EffectMode
import com.dd3boh.outertune.transition.model.EqMode
import com.dd3boh.outertune.transition.model.OverlapMode

@Entity(
    tableName = "transitions",
    primaryKeys = ["fromSongId", "toSongId"]
)
data class TransitionEntity(
    val fromSongId: String,
    val toSongId: String,

    /** Point in Song A (outgoing) where the transition zone starts. */
    val exitPointMs: Long,

    /** Point in Song B (incoming) aligned with [exitPointMs]. */
    val entryPointMs: Long,

    /** Length of the transition zone in beats of Song A (bars × 4). */
    val transitionDurationBeats: Double = DEFAULT_DURATION_BEATS,

    val syncTempo: Boolean = true,
    val type: Int = TYPE_MANUAL,

    val overlapMode: OverlapMode = OverlapMode.OVERLAP,
    val eqMode: EqMode = EqMode.NONE,
    val effectMode: EffectMode = EffectMode.NONE,

    // --- Persisted TransitionPlan (the editor's synchronisation contract) ---
    // When planVersion != null the playback engine trusts these values verbatim instead of
    // re-deriving them from BPM. See TransitionMath.calculatePlan / TransitionEditorViewModel.
    val planVersion: Int? = null,

    /** Playback speed multiplier applied to Track B (e.g. 1.02 = 2% faster). */
    val initialSpeedB: Double? = null,

    /** Grid compression factor for interval-matched transitions (1.0 = strict tempo match). */
    val gridScalarB: Double? = null,

    /** Editor waveform scroll offsets (beats) — used only to restore the editor UI exactly. */
    val offsetBeatsA: Double? = null,
    val offsetBeatsB: Double? = null,
) {
    /** Bars in the transition zone, for the editor's bar selector. */
    val bars: Int
        get() = (transitionDurationBeats / 4).toInt().coerceAtLeast(1)

    companion object {
        const val TYPE_MANUAL = 0
        const val TYPE_AUTO = 1

        const val DEFAULT_DURATION_BEATS = 16.0

        /**
         * Bump when the persisted plan fields change meaning.
         * 2: exit/entry points are against the canonical beat grid (BeatGridRepository).
         */
        const val PLAN_VERSION = 2
    }
}
