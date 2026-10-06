package com.dd3boh.outertune.transition.engine

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.transition.model.Deck
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Plays one transition on two players: starts the incoming deck and phase-locks it to the
 * outgoing deck ([MixController]), while each deck's [DeckAutomation] shapes its sound inside its
 * own audio pipeline, sample-accurately. This is the single implementation used by both the
 * editor preview and playlist playback.
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
     * @param outgoingGain, incomingGain each deck's player volume (loudness normalization × user
     *  volume), read every tick so a volume change during the transition applies to both
     * @param onFrame every tick's decisions, for UI and logical state
     */
    suspend fun run(
        outgoing: ExoPlayer,
        incoming: ExoPlayer,
        plan: TransitionPlan,
        config: () -> TransitionConfig,
        outgoingGain: () -> Float,
        incomingGain: () -> Float,
        diagnosticsLabel: String,
        onFrame: (MixController.Frame) -> Unit = {},
    ): Result {
        val speedA = outgoing.playbackParameters.speed
        // The plan's ratio assumes A plays at 1x; A may still be easing back from its own mix.
        val controller = MixController(plan, plan.initialSpeedB * speedA)
        val diagnostics = MixDiagnostics(TAG, diagnosticsLabel)

        incoming.volume = 0f
        // When B enters within the preroll's length of its own start, its target is still before
        // the song begins: B waits, paused at 0, and starts once A reaches that point.
        val startPositionAMs = outgoing.currentPosition + MixTuning.SEEK_LEAD_MS
        var waitingForB = controller.isBeforeStartOfB(startPositionAMs)
        seekUnlessNear(incoming, if (waitingForB) 0L else controller.targetPositionBMs(startPositionAMs))
        incoming.setPlaybackSpeed(controller.appliedSpeed.toFloat())
        incoming.playWhenReady = !waitingForB

        // Shape both decks from inside their audio pipelines; rebuilt if the config changes.
        var installedConfig = config()
        effects.setAutomation(outgoing, DeckAutomation(plan, installedConfig, Deck.A))
        effects.setAutomation(incoming, DeckAutomation(plan, installedConfig, Deck.B))
        outgoing.volume = outgoingGain()

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

                // A failed deck (IDLE with an error) is neither playing nor ended, so without this
                // the playing-time budget would never run out.
                if (outgoing.playbackState == Player.STATE_ENDED || outgoing.playbackState == Player.STATE_IDLE ||
                    outgoing.playerError != null || outgoing.currentMediaItem?.mediaId != startMediaId
                ) {
                    return Result.OUTGOING_ENDED.also { diagnostics.finish(it.name) }
                }
                if (playingMs > budgetMs) return Result.TIMED_OUT.also { diagnostics.finish(it.name) }

                val currentConfig = config()
                if (currentConfig != installedConfig) {
                    installedConfig = currentConfig
                    effects.setAutomation(outgoing, DeckAutomation(plan, currentConfig, Deck.A))
                    effects.setAutomation(incoming, DeckAutomation(plan, currentConfig, Deck.B))
                }

                if (waitingForB) {
                    if (controller.isBeforeStartOfB(outgoing.currentPosition + MixTuning.SEEK_LEAD_MS)) {
                        // Linking mirrors A's play/pause onto B; hold B until its start.
                        if (incoming.playWhenReady) incoming.playWhenReady = false
                    } else {
                        waitingForB = false
                        seekUnlessNear(incoming, 0L)
                        incoming.playWhenReady = outgoing.playWhenReady
                    }
                }

                val frame = controller.step(
                    nowMs = now,
                    positionAMs = outgoing.currentPosition,
                    positionBMs = incoming.currentPosition,
                    incomingPlaying = incoming.isPlaying,
                    incomingStarted = !waitingForB,
                )
                onFrame(frame)

                if (frame.stage == MixController.Stage.DONE) {
                    outgoing.volume = 0f
                    incoming.volume = incomingGain()
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

                if (frame.stage == MixController.Stage.CROSSFADE && !waitingForB) {
                    if (!unmuted) {
                        unmuted = true
                        diagnostics.onUnmute(frame.phaseErrorBeats)
                    }
                    diagnostics.onCrossfadeSample(frame.phaseErrorBeats)
                }
                // The automation already silences B before the zone; its player volume stays at
                // 0 until then as well, in case the processor is bypassed.
                setVolume(outgoing, outgoingGain())
                setVolume(incoming, if (unmuted) incomingGain() else 0f)

                delay(MixTuning.TICK_MS)
            }
            return Result.TIMED_OUT
        } finally {
            effects.setAutomation(outgoing, null)
            effects.setAutomation(incoming, null)
        }
    }

    /** Seeks [player] to [targetMs] unless it is already close: a prepared deck keeps its buffer. */
    private fun seekUnlessNear(player: ExoPlayer, targetMs: Long) {
        if (abs(player.currentPosition - targetMs) > MixTuning.START_SEEK_TOLERANCE_MS) player.seekTo(targetMs)
    }

    private fun setVolume(player: ExoPlayer, volume: Float) {
        if (player.volume != volume) player.volume = volume
    }

    private companion object {
        const val TAG = "TransitionRenderer"
    }
}
