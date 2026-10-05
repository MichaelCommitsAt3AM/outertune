package com.dd3boh.outertune.transition.engine

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Plays one transition on two players: starts the incoming deck, phase-locks it to the outgoing
 * deck and runs the volume/EQ automation, all decided by [MixController]. This is the single
 * implementation used by both the editor preview and playlist playback.
 *
 * It owns no state beyond one run: what happens afterwards (swapping decks, looping the preview)
 * is up to the caller. Must run on the main thread. Cancelling the coroutine stops the run; the
 * caller decides what to do with the decks then.
 */
class TransitionRenderer(private val effects: DeckEffects) {

    enum class Result {
        /** The zone ended normally. */
        COMPLETED,
        /** The outgoing deck ended or moved to another item before the zone did. */
        OUTGOING_ENDED,
        /** The run took far longer than the zone should (a deck stalled). */
        TIMED_OUT,
    }

    /**
     * Preconditions: [outgoing] is playing, at or before the preroll; [incoming] holds the next
     * song and is prepared.
     *
     * @param config read every tick, so edits apply live (editor preview)
     * @param outgoingGain, incomingGain final volumes (loudness normalization × user volume)
     * @param onFrame every tick's decisions, for UI and logical state
     */
    suspend fun run(
        outgoing: ExoPlayer,
        incoming: ExoPlayer,
        plan: TransitionPlan,
        config: () -> TransitionConfig,
        outgoingGain: Float,
        incomingGain: Float,
        diagnosticsLabel: String,
        onFrame: (MixController.Frame) -> Unit = {},
    ): Result {
        val speedA = outgoing.playbackParameters.speed
        // The plan's ratio assumes A plays at 1x; A may still be easing back from its own mix.
        val controller = MixController(plan, plan.initialSpeedB * speedA)
        val diagnostics = MixDiagnostics(TAG, diagnosticsLabel)

        incoming.volume = 0f
        incoming.seekTo(controller.targetPositionBMs(outgoing.currentPosition + MixTuning.SEEK_LEAD_MS))
        incoming.setPlaybackSpeed(controller.appliedSpeed.toFloat())
        incoming.playWhenReady = true
        incoming.play()

        val startMediaId = outgoing.currentMediaItem?.mediaId
        // Counts only time A is actually playing, so pausing mid-transition is fine.
        val budgetMs = controller.expectedDurationMs(speedA) + MixTuning.BUDGET_SLACK_MS
        var playingMs = 0L
        var lastTick = System.currentTimeMillis()
        var unmuted = false

        try {
            delay(MixTuning.SEEK_SETTLE_MS)

            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (outgoing.isPlaying) playingMs += now - lastTick
                lastTick = now

                if (outgoing.playbackState == Player.STATE_ENDED || outgoing.currentMediaItem?.mediaId != startMediaId) {
                    return Result.OUTGOING_ENDED.also { diagnostics.finish(it.name) }
                }
                if (playingMs > budgetMs) return Result.TIMED_OUT.also { diagnostics.finish(it.name) }

                val frame = controller.step(
                    nowMs = now,
                    positionAMs = outgoing.currentPosition,
                    positionBMs = incoming.currentPosition,
                    incomingPlaying = incoming.isPlaying,
                    config = config(),
                )
                onFrame(frame)

                if (frame.stage == MixController.Stage.DONE) {
                    outgoing.volume = 0f
                    incoming.volume = incomingGain
                    return Result.COMPLETED.also { diagnostics.finish(it.name) }
                }

                frame.reseekBMs?.let { target ->
                    diagnostics.onReseek()
                    incoming.seekTo(target)
                    incoming.setPlaybackSpeed(controller.appliedSpeed.toFloat())
                }
                frame.speedB?.let { speed ->
                    incoming.setPlaybackSpeed(speed.toFloat())
                    diagnostics.onSpeedChange()
                }

                if (frame.stage == MixController.Stage.CROSSFADE) {
                    if (!unmuted) {
                        unmuted = true
                        diagnostics.onUnmute(frame.phaseErrorBeats)
                    }
                    diagnostics.onCrossfadeSample(frame.phaseErrorBeats)
                    val effectMode = config().effectMode
                    effects.apply(outgoing, frame.stateA, effectMode)
                    effects.apply(incoming, frame.stateB, effectMode)
                }
                outgoing.volume = frame.volumeA * outgoingGain
                incoming.volume = frame.volumeB * incomingGain

                delay(MixTuning.TICK_MS)
            }
            return Result.TIMED_OUT
        } finally {
            effects.reset(outgoing)
            effects.reset(incoming)
        }
    }

    private companion object {
        const val TAG = "TransitionRenderer"
    }
}
