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
     * @property durationMs Duration of the transition in milliseconds
     * @property overlapMode The overlap mixing mode (e.g., "Overlap", "Crossfade", "Cut")
     * @property eqMode The EQ mode applied during transition
     * @property effectMode The effect mode applied during transition
     * @property type Transition type from TransitionEntity (MANUAL or AUTO)
     */
    data class Custom(
        val durationMs: Long,
        val overlapMode: String,
        val eqMode: String,
        val effectMode: String,
        val type: Int
    ) : TransitionState() {
        /**
         * Returns a human-readable duration string.
         * Example: "8s", "12.5s"
         */
        fun getDurationString(): String {
            val seconds = durationMs / 1000.0
            return if (seconds % 1.0 == 0.0) {
                "${seconds.toInt()}s"
            } else {
                String.format("%.1fs", seconds)
            }
        }

        /**
         * Returns true if this is a manually created transition.
         */
        fun isManual(): Boolean = type == 0 // TYPE_MANUAL from TransitionEntity
    }
}
