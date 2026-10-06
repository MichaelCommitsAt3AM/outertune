package com.dd3boh.outertune.transition.playback

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
        /** Proportional gain in fractional speed per beat of error. */
        const val PREROLL_KP = 0.5
        const val CROSSFADE_KP = 0.3

        /** Largest relative speed deviation from [baseSpeed]. B is muted in preroll. */
        const val PREROLL_MAX_NUDGE = 0.2
        const val CROSSFADE_MAX_NUDGE = 0.04

        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0

        /** Errors below this (about 5 ms at 120 BPM) are left alone. */
        const val DEAD_BAND_BEATS = 0.01
        const val MIN_SPEED_STEP = 0.002
        const val MIN_UPDATE_INTERVAL_MS = 100L

        const val RESEEK_THRESHOLD_BEATS = 0.35
        const val RESEEK_MIN_BEATS_BEFORE_UNMUTE = 2.0
        const val MAX_RESEEKS = 2

        /** Maps an error to the nearest beat, in [-0.5, 0.5). */
        fun wrapToNearestBeat(error: Double): Double = error - floor(error + 0.5)
    }
}
