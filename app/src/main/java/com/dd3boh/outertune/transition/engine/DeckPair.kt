package com.dd3boh.outertune.transition.engine

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.dd3boh.outertune.utils.DebugLog as Log

/**
 * Two players with an active and a standby role. Holds no transition state: the caller decides
 * when to prepare the standby deck, runs the transition ([TransitionRenderer]) and calls [swap].
 *
 * Audio focus is handled by the active deck only, and moves with the role on [swap], so the
 * standby deck never takes focus from the deck the user hears. Must be used on the main thread.
 */
class DeckPair(private val factory: DeckFactory) {

    val playerA: ExoPlayer = factory.create(handleAudioFocus = true)
    private var playerBOrNull: ExoPlayer? = null
    private val playerB: ExoPlayer
        get() = playerBOrNull ?: factory.create(handleAudioFocus = false).also {
            Log.d(TAG, "Creating Deck B")
            playerBOrNull = it
            linkPlayWhenReady(it)
        }

    var active: ExoPlayer = playerA
        private set

    /** The deck that is not active. Creates Deck B on first use. */
    val standby: ExoPlayer
        get() = if (active === playerA) playerB else playerA

    /**
     * While true, pausing or resuming the active deck (by the user, a focus loss, headphones
     * unplugged) does the same to the standby deck. Set it for the length of a transition.
     */
    var linked = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var prepareJob: Job? = null
    private var speedJob: Job? = null

    init {
        linkPlayWhenReady(playerA)
    }

    private fun linkPlayWhenReady(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (linked && player === active) {
                    val other = standby
                    if (other.playWhenReady != playWhenReady) other.playWhenReady = playWhenReady
                }
            }
        })
    }

    /**
     * Loads [mediaItem] on the standby deck, paused at [startPositionMs], so a transition can
     * start from buffered audio.
     */
    fun prepareStandby(mediaItem: MediaItem, startPositionMs: Long, speed: Float = 1f) {
        prepareJob?.cancel()
        val deck = standby
        deck.stop()
        deck.clearMediaItems()
        deck.setMediaItem(mediaItem)
        deck.volume = 0f
        deck.playWhenReady = false
        deck.seekTo(startPositionMs)
        deck.setPlaybackSpeed(speed)
        deck.prepare()

        prepareJob = scope.launch {
            if (!awaitReady(deck)) Log.w(TAG, "Standby deck did not become ready in time")
        }
    }

    /** Suspends until [deck] is ready to play; false on timeout. */
    suspend fun awaitReady(deck: ExoPlayer, timeoutMs: Long = MixTuning.PREPARE_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (deck.playbackState != Player.STATE_READY) delay(20)
            true
        } ?: false

    /**
     * Makes the standby deck active (after a completed transition), clears the old one and eases
     * the new active deck back to its natural speed.
     */
    fun swap() {
        prepareJob?.cancel()
        linked = false
        val old = active
        val new = standby
        active = new

        old.setAudioAttributes(DeckFactory.AUDIO_ATTRIBUTES, false)
        new.setAudioAttributes(DeckFactory.AUDIO_ATTRIBUTES, true)

        old.stop()
        old.clearMediaItems()
        old.volume = 0f
        old.setPlaybackSpeed(1f)

        resetSpeed(new)
    }

    /** Ramps [player] back to 1x over [MixTuning.SPEED_RESET_MS]; snaps if it's already close. */
    private fun resetSpeed(player: ExoPlayer) {
        speedJob?.cancel()
        val startSpeed = player.playbackParameters.speed
        if (abs(startSpeed - 1f) < 0.01f) {
            if (startSpeed != 1f) player.setPlaybackSpeed(1f)
            return
        }
        speedJob = scope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                val progress = (System.currentTimeMillis() - start).toFloat() / MixTuning.SPEED_RESET_MS
                if (progress >= 1f) break
                player.setPlaybackSpeed(startSpeed + (1f - startSpeed) * progress)
                // Coarse steps give the time-stretcher time to settle between changes.
                delay(500)
            }
            player.setPlaybackSpeed(1f)
        }
    }

    fun release() {
        scope.cancel()
        playerA.release()
        playerBOrNull?.release()
    }

    private companion object {
        const val TAG = "DeckPair"
    }
}
