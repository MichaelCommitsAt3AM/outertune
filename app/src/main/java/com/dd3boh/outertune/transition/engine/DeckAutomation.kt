package com.dd3boh.outertune.transition.engine

import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.Deck
import com.dd3boh.outertune.transition.model.EffectMode
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan

/**
 * The transition's shape for one deck, as a function of that deck's own media time.
 *
 * Applied inside the deck's audio pipeline ([DeckAudioProcessor]) to each block of samples, so
 * fades, bass swaps, filter sweeps and sidechain ducking land on the right sample of the song
 * regardless of output latency or the control loop's timing. Pure Kotlin.
 *
 * Progress is measured in Track A's beats. Track B maps its own position onto A's beats through
 * the plan (`elapsedA = (beatB - anchorB) × gridScalar`), which is exact while B is phase-locked.
 */
class DeckAutomation(
    private val plan: TransitionPlan,
    private val config: TransitionConfig,
    private val deck: Deck,
) {
    private val gridScalar = plan.gridScalarB.takeIf { it > 0.0 } ?: 1.0

    val effectMode: EffectMode get() = config.effectMode

    /** Progress through the zone (A's beats) at [mediaTimeSec] on this deck. */
    fun progressAt(mediaTimeSec: Double): Double = elapsedBeatsA(mediaTimeSec) / plan.transitionDurationBeats

    /** What this deck should sound like at [mediaTimeSec] of its own song. */
    fun stateAt(mediaTimeSec: Double): DeckState {
        val elapsedA = elapsedBeatsA(mediaTimeSec)
        val progress = elapsedA / plan.transitionDurationBeats
        return when {
            // Before the zone: A untouched, B silent.
            progress < 0 -> if (deck == Deck.A) DeckState.NEUTRAL else SILENT
            // After the zone: A silent (until the decks swap), B untouched.
            progress > 1 -> if (deck == Deck.A) SILENT else DeckState.NEUTRAL
            else -> {
                val (posA, posB) = when (deck) {
                    Deck.A -> mediaTimeSec to TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB + elapsedA / gridScalar)
                    Deck.B -> TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA + elapsedA) to mediaTimeSec
                }
                TransitionMixer.getMixState(
                    deck, progress.toFloat(), config.overlapMode, config.eqMode, config.effectMode,
                    positionA = posA, positionB = posB, beatGridA = plan.gridA, beatGridB = plan.gridB
                )
            }
        }
    }

    private fun elapsedBeatsA(mediaTimeSec: Double): Double = when (deck) {
        Deck.A -> TransitionMath.getBeatForTimestamp(plan.gridA, mediaTimeSec) - plan.anchorBeatA
        Deck.B -> (TransitionMath.getBeatForTimestamp(plan.gridB, mediaTimeSec) - plan.anchorBeatB) * gridScalar
    }

    private companion object {
        val SILENT = DeckState(volume = 0f)
    }
}
