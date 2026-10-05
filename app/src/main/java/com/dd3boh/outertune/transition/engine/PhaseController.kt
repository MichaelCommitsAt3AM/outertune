package com.dd3boh.outertune.transition.engine

import kotlin.math.abs
import kotlin.math.floor

/**
 * Speed control law that keeps Track B phase-locked to Track A during a transition.
 *
 * Pure Kotlin so it can be unit tested and shared by the editor preview and playlist playback.
 *
 * The plant has a large delay: a speed change only becomes audible (and visible in the player's
 * position) once the audio already buffered at the old speed has drained, typically 200-400 ms.
 * So corrections are proportional only, rate-limited, and skip tiny steps; reacting faster than
 * the delay just makes the loop oscillate and makes the time-stretcher work harder.
 *
 * @param baseSpeed speed at which B's beats advance at the same rate as A's (the plan's
 *  synchronisation ratio, times A's own current speed).
 */
class PhaseController(private val baseSpeed: Double) {

    enum class Stage {
        /** B is playing but muted: correct aggressively, re-seek if far off. */
        PREROLL,
        /** B is audible: correct gently and lock to the nearest beat. */
        CROSSFADE,
    }

    var appliedSpeed: Double = baseSpeed
        private set

    private var lastChangeAtMs = Long.MIN_VALUE / 2
    private var reseeks = 0

    /**
     * @param phaseErrorBeats target beat minus current beat on B (positive = B is behind)
     * @return the speed to apply now, or null to leave B's speed unchanged
     */
    fun speedFor(stage: Stage, phaseErrorBeats: Double, nowMs: Long): Double? {
        if (nowMs - lastChangeAtMs < MIN_UPDATE_INTERVAL_MS) return null

        val error = if (stage == Stage.CROSSFADE) wrapToNearestBeat(phaseErrorBeats) else phaseErrorBeats
        val (kp, maxNudge) = when (stage) {
            Stage.PREROLL -> PREROLL_KP to PREROLL_MAX_NUDGE
            Stage.CROSSFADE -> CROSSFADE_KP to CROSSFADE_MAX_NUDGE
        }
        val nudge = if (abs(error) < DEAD_BAND_BEATS) 0.0 else (error * kp).coerceIn(-maxNudge, maxNudge)
        val target = (baseSpeed * (1.0 + nudge)).coerceIn(MIN_SPEED, MAX_SPEED)

        if (abs(target - appliedSpeed) < MIN_SPEED_STEP) return null
        appliedSpeed = target
        lastChangeAtMs = nowMs
        return target
    }

    /**
     * Whether B is so far off during preroll that a seek beats speed correction. Counts the
     * re-seek and resets the applied speed to [baseSpeed] when it returns true; the caller must
     * then seek B and set its speed to [appliedSpeed].
     *
     * @param beatsUntilUnmute beats of A left before B becomes audible
     */
    fun shouldReseek(phaseErrorBeats: Double, beatsUntilUnmute: Double, nowMs: Long): Boolean {
        if (reseeks >= MAX_RESEEKS) return false
        if (beatsUntilUnmute < RESEEK_MIN_BEATS_BEFORE_UNMUTE) return false
        if (abs(phaseErrorBeats) < RESEEK_THRESHOLD_BEATS) return false
        reseeks++
        appliedSpeed = baseSpeed
        lastChangeAtMs = nowMs
        return true
    }

    companion object {
        const val PREROLL_KP = MixTuning.PREROLL_KP
        const val CROSSFADE_KP = MixTuning.CROSSFADE_KP
        const val PREROLL_MAX_NUDGE = MixTuning.PREROLL_MAX_NUDGE
        const val CROSSFADE_MAX_NUDGE = MixTuning.CROSSFADE_MAX_NUDGE
        const val MIN_SPEED = MixTuning.MIN_SPEED
        const val MAX_SPEED = MixTuning.MAX_SPEED
        const val DEAD_BAND_BEATS = MixTuning.DEAD_BAND_BEATS
        const val MIN_SPEED_STEP = MixTuning.MIN_SPEED_STEP
        const val MIN_UPDATE_INTERVAL_MS = MixTuning.MIN_SPEED_UPDATE_INTERVAL_MS
        const val RESEEK_THRESHOLD_BEATS = MixTuning.RESEEK_THRESHOLD_BEATS
        const val RESEEK_MIN_BEATS_BEFORE_UNMUTE = MixTuning.RESEEK_MIN_BEATS_BEFORE_UNMUTE
        const val MAX_RESEEKS = MixTuning.MAX_RESEEKS

        /** Maps an error to the nearest beat, in [-0.5, 0.5). */
        fun wrapToNearestBeat(error: Double): Double = error - floor(error + 0.5)
    }
}
