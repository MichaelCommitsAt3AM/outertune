package com.dd3boh.outertune.transition.engine

import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionPlan

/**
 * Decides, tick by tick, how Track B is kept in phase with Track A during a transition (its
 * speed, and re-seeks during the preroll), and where the transition is. Pure Kotlin: positions
 * in, decisions out. The sound itself is shaped by [DeckAutomation] inside each deck's pipeline.
 *
 * Beat positions: Track B's target beat is `anchorB + elapsedBeatsA / gridScalar`, which also
 * covers interval-matched pairs (e.g. 70 vs 140 BPM, where one B beat spans two A beats).
 * Progress through the zone is measured in A's beats.
 *
 * @param baseSpeed speed at which B's beats keep pace with A's (the plan's ratio times A's speed)
 */
class MixController(
    private val plan: TransitionPlan,
    baseSpeed: Double,
) {
    enum class Stage { PREROLL, CROSSFADE, DONE }

    /** What to do on this tick. */
    data class Frame(
        val stage: Stage,
        /** < 0 in preroll, 0..1 across the zone. */
        val progress: Float,
        val beatA: Double,
        val beatB: Double,
        /** Target minus actual beat on B; wrapped to the nearest beat once B is audible. */
        val phaseErrorBeats: Double,
        /** New speed for B, or null to leave it. */
        val speedB: Double?,
        /** Seek B here (ms) and set its speed to [MixController.appliedSpeed], or null. */
        val reseekBMs: Long?,
    )

    private val gridScalar = plan.gridScalarB.takeIf { it > 0.0 } ?: 1.0
    private val phase = PhaseController(baseSpeed)

    /** B's current target speed. */
    val appliedSpeed: Double get() = phase.appliedSpeed

    /** B's beat at the very start of its song (negative when its first beat comes later). */
    private val startBeatB = TransitionMath.getBeatForTimestamp(plan.gridB, 0.0)

    /** Where B should be (ms) when A is at [positionAMs]; 0 while that is before B's start. */
    fun targetPositionBMs(positionAMs: Long): Long {
        val timeB = TransitionMath.getTimestampForBeat(plan.gridB, targetBeatB(positionAMs))
        return (timeB * 1000).toLong().coerceAtLeast(0)
    }

    /**
     * Whether B's target is still before the start of its song when A is at [positionAMs]: B
     * enters so close to its start that the preroll begins before it. B must then wait, paused
     * at 0, rather than start early.
     */
    fun isBeforeStartOfB(positionAMs: Long): Boolean = targetBeatB(positionAMs) < startBeatB

    private fun targetBeatB(positionAMs: Long): Double {
        val elapsedBeatsA = TransitionMath.getBeatForTimestamp(plan.gridA, positionAMs / 1000.0) - plan.anchorBeatA
        return plan.anchorBeatB + elapsedBeatsA / gridScalar
    }

    /** Wall-clock length (ms) of the zone plus preroll on A at [speedA]. */
    fun expectedDurationMs(speedA: Float): Long {
        val anchorTime = TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA)
        val endTime = TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA + plan.transitionDurationBeats)
        return ((endTime - anchorTime + MixTuning.PREROLL_SECONDS) * 1000 / speedA.coerceAtLeast(0.1f)).toLong()
    }

    /**
     * @param positionAMs A's current position
     * @param positionBMs B's current position
     * @param incomingPlaying whether B is actually playing (re-seeks only make sense then)
     * @param incomingStarted false while B waits for its start ([isBeforeStartOfB]): no
     *  corrections are made, since B's position means nothing yet
     */
    fun step(
        nowMs: Long,
        positionAMs: Long,
        positionBMs: Long,
        incomingPlaying: Boolean,
        incomingStarted: Boolean = true,
    ): Frame {
        val posA = positionAMs / 1000.0
        val posB = positionBMs / 1000.0
        val beatA = TransitionMath.getBeatForTimestamp(plan.gridA, posA)
        val beatB = TransitionMath.getBeatForTimestamp(plan.gridB, posB)
        val elapsedBeatsA = beatA - plan.anchorBeatA
        val rawError = plan.anchorBeatB + elapsedBeatsA / gridScalar - beatB
        val progress = (elapsedBeatsA / plan.transitionDurationBeats).toFloat()

        val stage = when {
            progress > 1f -> Stage.DONE
            progress < 0f -> Stage.PREROLL
            else -> Stage.CROSSFADE
        }

        var speed: Double? = null
        var reseek: Long? = null
        if (incomingStarted) when (stage) {
            Stage.PREROLL ->
                if (incomingPlaying && phase.shouldReseek(rawError, -elapsedBeatsA, nowMs)) {
                    reseek = targetPositionBMs(positionAMs + MixTuning.SEEK_LEAD_MS)
                } else {
                    speed = phase.speedFor(PhaseController.Stage.PREROLL, rawError, nowMs)
                }
            Stage.CROSSFADE -> speed = phase.speedFor(PhaseController.Stage.CROSSFADE, rawError, nowMs)
            Stage.DONE -> Unit
        }

        return Frame(
            stage = stage,
            progress = progress,
            beatA = beatA,
            beatB = beatB,
            phaseErrorBeats = when {
                !incomingStarted -> 0.0
                stage == Stage.PREROLL -> rawError
                else -> PhaseController.wrapToNearestBeat(rawError)
            },
            speedB = speed,
            reseekBMs = reseek,
        )
    }
}
