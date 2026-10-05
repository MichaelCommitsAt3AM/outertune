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

    /** Where B should be (ms) when A is at [positionAMs]. */
    fun targetPositionBMs(positionAMs: Long): Long {
        val elapsedBeatsA = TransitionMath.getBeatForTimestamp(plan.gridA, positionAMs / 1000.0) - plan.anchorBeatA
        val timeB = TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB + elapsedBeatsA / gridScalar)
        return (timeB * 1000).toLong().coerceAtLeast(0)
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
     */
    fun step(nowMs: Long, positionAMs: Long, positionBMs: Long, incomingPlaying: Boolean): Frame {
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
        when (stage) {
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
            phaseErrorBeats = if (stage == Stage.PREROLL) rawError else PhaseController.wrapToNearestBeat(rawError),
            speedB = speed,
            reseekBMs = reseek,
        )
    }
}
