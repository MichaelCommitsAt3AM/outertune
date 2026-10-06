package com.dd3boh.outertune.transition.engine

/**
 * Every tunable number of the mix engine, in one place. The editor preview and playlist playback
 * run the same engine with these values, so tuning one tunes both.
 */
object MixTuning {
    // --- Timing ---

    /** Seconds of Track A before the zone during which B plays muted and gets phase-locked. */
    const val PREROLL_SECONDS = 3.0

    /** Control loop period. */
    const val TICK_MS = 20L

    /** How far ahead B is seeked, to cover the time the seek itself takes. */
    const val SEEK_LEAD_MS = 50L

    /** B is not re-seeked at the start of a transition if it is already this close to its target. */
    const val START_SEEK_TOLERANCE_MS = 80L

    /** Wait after the initial seek before the first tick reads B's position. */
    const val SEEK_SETTLE_MS = 50L

    /** Extra wall-clock time (beyond the zone's own length) before a stalled transition is abandoned. */
    const val BUDGET_SLACK_MS = 5_000L

    /** How long the standby deck may take to buffer. */
    const val PREPARE_TIMEOUT_MS = 15_000L

    /** Playlist: build the plan this long before the exit point. */
    const val ARM_LEAD_MS = 15_000L

    /** Playlist: shortest and longest sleep of the mix state machine between checks. */
    const val POLLER_MIN_WAKE_MS = 20L
    const val POLLER_MAX_SLEEP_MS = 5_000L

    /** Playlist: crossfade progress at which the UI switches to the incoming song. */
    const val UI_SWITCH_PROGRESS = 0.5f

    /** Playlist: time over which the new active deck eases back to 1x after a transition. */
    const val SPEED_RESET_MS = 10_000L

    /** Editor preview: Track A starts this long before the preroll, so it is settled when the engine takes over. */
    const val PREVIEW_LEAD_IN_SECONDS = 1.0

    /** Editor preview: Track B plays on alone this long after the zone, so the result can be heard. */
    const val PREVIEW_TAIL_SECONDS = 4.0

    // --- Phase lock (see PhaseController) ---

    /** Proportional gain in fractional speed per beat of error. */
    const val PREROLL_KP = 0.5
    const val CROSSFADE_KP = 0.3

    /** Largest relative speed deviation from the base speed. B is muted in preroll. */
    const val PREROLL_MAX_NUDGE = 0.2
    const val CROSSFADE_MAX_NUDGE = 0.04

    const val MIN_SPEED = 0.5
    const val MAX_SPEED = 2.0

    /** Errors below this (about 5 ms at 120 BPM) are left alone. */
    const val DEAD_BAND_BEATS = 0.01
    const val MIN_SPEED_STEP = 0.002

    /** Speed changes are only audible after the buffered audio drains; don't react faster. */
    const val MIN_SPEED_UPDATE_INTERVAL_MS = 100L

    const val RESEEK_THRESHOLD_BEATS = 0.35
    const val RESEEK_MIN_BEATS_BEFORE_UNMUTE = 2.0
    const val MAX_RESEEKS = 2

    // --- Effects ---

    /** Equalizer writes smaller than this (millibels) are skipped. */
    const val EQ_MIN_LEVEL_STEP_MB = 30
}
