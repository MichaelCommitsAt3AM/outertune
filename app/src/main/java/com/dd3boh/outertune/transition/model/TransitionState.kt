package com.dd3boh.outertune.transition.model

/**
 * Represents the state of a transition between two tracks.
 *
 * This sealed class provides a type-safe way to distinguish between
 * system-generated (AUTO) and user-defined (CUSTOM) transitions.
 *
 * Usage in UI:
 * ```
 * when (state) {
 *     is TransitionState.Auto -> {
 *         // Render neutral chip with "AUTO" label
 *     }
 *     is TransitionState.Custom -> {
 *         // Render accent chip with "CUSTOM" label
 *         // Access metadata: state.durationMs, state.overlapMode, etc.
 *     }
 * }
 * ```
 */
sealed class TransitionState {
    /**
     * No user-defined transition exists.
     * The system will generate a default transition if needed.
     */
    data object Auto : TransitionState()

    /**
     * A user-defined transition has been configured and saved.
     *
     * @property bars Length of the transition zone in bars
     * @property overlapMode The overlap mixing mode (e.g., "Overlap", "Crossfade", "Cut")
     * @property eqMode The EQ mode applied during transition
     * @property effectMode The effect mode applied during transition
     * @property type Transition type from TransitionEntity (MANUAL or AUTO)
     */
    data class Custom(
        val bars: Int,
        val overlapMode: OverlapMode,
        val eqMode: EqMode,
        val effectMode: EffectMode,
        val type: Int
    ) : TransitionState() {
        /**
         * Returns true if this is a manually created transition.
         */
        fun isManual(): Boolean = type == com.dd3boh.outertune.db.entities.TransitionEntity.TYPE_MANUAL
    }
}
