package com.dd3boh.outertune.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The one player the media session and the app UI use, whatever plays underneath.
 *
 * It mirrors the engine's active player, except that from a mix transition's midpoint it already
 * reports the incoming song as current, with the incoming deck's position. Notification, lock
 * screen, Bluetooth, the in-app player and lyrics therefore all switch songs together, and none
 * of them sees decks being swapped or engines being replaced.
 *
 * Commands go to the engine, which decides what they mean mid-transition. Must be used on the
 * main thread.
 */
class MixSessionPlayer(
    /** The user's volume (0..1). */
    private val userVolume: () -> Float,
    private val onUserVolumeChange: (Float) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var engine: PlaybackEngine? = null
    private var listenedPlayer: ExoPlayer? = null
    private var pendingDiscontinuity: Pair<Int, Long>? = null

    /** The engine's active player, read live: it changes before [bind] is called on a swap. */
    private val player: ExoPlayer?
        get() = engine?.activePlayer?.value ?: listenedPlayer

    private val handler = Handler(Looper.getMainLooper())
    private var refreshPosted = false

    /**
     * Refreshes the state once the current main-thread turn is done. A deck swap changes several
     * things in one go (active deck, its queue, the transition flag); reading the state halfway
     * would briefly show the old song or a one-item queue.
     */
    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        handler.post {
            refreshPosted = false
            invalidateState()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            scheduleRefresh()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Only real jumps; an auto transition is reported through the item change itself.
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                pendingDiscontinuity = reason to newPosition.positionMs
            }
        }
    }

    /** Follows [player] as the engine's active player. Called on every engine switch and deck swap. */
    fun bind(engine: PlaybackEngine, player: ExoPlayer) {
        if (engine !== this.engine) {
            this.engine?.onLogicalStateChanged = null
            engine.onLogicalStateChanged = ::scheduleRefresh
            this.engine = engine
        }
        if (player !== listenedPlayer) {
            listenedPlayer?.removeListener(listener)
            player.addListener(listener)
            listenedPlayer = player
        }
        scheduleRefresh()
    }

    /** Re-reads the state after a change that raised no player event (e.g. the user volume). */
    fun refresh() = scheduleRefresh()

    override fun getState(): State {
        val p = player ?: return State.Builder().setAvailableCommands(COMMANDS).build()
        val engine = engine

        val playlist = buildPlaylist(p.currentTimeline)
        val offset = engine?.logicalIndexOffset ?: 0
        val index = (p.currentMediaItemIndex + offset).coerceIn(0, (playlist.size - 1).coerceAtLeast(0))

        // The current song's duration is its logical one (in mix mode it ends at the exit point).
        if (playlist.isNotEmpty() && engine != null) {
            val durationMs = engine.logicalDurationMs()
            if (durationMs > 0) {
                playlist[index] = playlist[index].buildUpon().setDurationUs(durationMs * 1000).build()
            }
        }

        val playbackState = if (playlist.isEmpty() && p.playbackState != Player.STATE_ENDED) {
            Player.STATE_IDLE
        } else p.playbackState

        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(p.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaybackSuppressionReason(p.playbackSuppressionReason)
            .setPlayerError(p.playerError)
            .setRepeatMode(p.repeatMode)
            .setShuffleModeEnabled(p.shuffleModeEnabled)
            .setIsLoading(p.isLoading)
            .setSeekBackIncrementMs(p.seekBackIncrement)
            .setSeekForwardIncrementMs(p.seekForwardIncrement)
            .setPlaybackParameters(p.playbackParameters)
            .setAudioAttributes(p.audioAttributes)
            .setVolume(userVolume())
            .setPlaylist(playlist)

        if (playlist.isNotEmpty()) {
            builder
                .setCurrentMediaItemIndex(index)
                .setContentPositionMs { this.engine?.logicalPositionMs() ?: p.currentPosition }
                .setContentBufferedPositionMs { this.engine?.let { maxOf(it.logicalPositionMs(), p.bufferedPosition) } ?: p.bufferedPosition }
        }

        pendingDiscontinuity?.let { (reason, positionMs) ->
            builder.setPositionDiscontinuity(reason, positionMs)
            pendingDiscontinuity = null
        }
        return builder.build()
    }

    /**
     * One entry per item of the active player's playlist. Uids are the media id plus its
     * occurrence, so a song keeps its identity when it moves from one deck to the other.
     */
    private fun buildPlaylist(timeline: Timeline): MutableList<MediaItemData> {
        val window = Timeline.Window()
        val seen = HashMap<String, Int>()
        val out = ArrayList<MediaItemData>(timeline.windowCount)
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            val mediaId = window.mediaItem.mediaId
            val occurrence = seen.merge(mediaId, 1, Int::plus)!!
            out.add(
                MediaItemData.Builder(mediaId to occurrence)
                    .setMediaItem(window.mediaItem)
                    .setDurationUs(window.durationUs)
                    .setIsSeekable(window.isSeekable)
                    .setIsDynamic(window.isDynamic)
                    .setIsPlaceholder(window.isPlaceholder)
                    .build()
            )
        }
        return out
    }

    // --- Commands ---

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        player?.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        player?.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        player?.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        // The service owns the engines and releases their players.
        listenedPlayer?.removeListener(listener)
        handler.removeCallbacksAndMessages(null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        player?.repeatMode = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        player?.shuffleModeEnabled = shuffleModeEnabled
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        player?.playbackParameters = playbackParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        onUserVolumeChange(volume)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val engine = engine ?: return Futures.immediateVoidFuture()
        engine.seekToItem(mediaItemIndex, positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val p = player ?: return Futures.immediateVoidFuture()
        if (startIndex == C.INDEX_UNSET) p.setMediaItems(mediaItems, true)
        else p.setMediaItems(mediaItems, startIndex, startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        player?.addMediaItems(index, mediaItems)
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        player?.moveMediaItems(fromIndex, toIndex, newIndex)
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        player?.replaceMediaItems(fromIndex, toIndex, mediaItems)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        player?.removeMediaItems(fromIndex, toIndex)
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_DEFAULT_POSITION,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_SEEK_BACK,
                COMMAND_SEEK_FORWARD,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_GET_AUDIO_ATTRIBUTES,
                COMMAND_GET_VOLUME,
                COMMAND_SET_VOLUME,
                COMMAND_RELEASE,
            )
            .build()
    }
}
