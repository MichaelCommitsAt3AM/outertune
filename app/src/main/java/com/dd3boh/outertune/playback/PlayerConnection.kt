/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.getCurrentQueueIndex
import com.dd3boh.outertune.extensions.getQueueWindows
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.utils.SemanticLyrics

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    binder: MediaControllerViewModel,
    val database: MusicDatabase,
) : Player.Listener {
    val TAG = PlayerConnection::class.simpleName.toString()

    val service = binder.getService()!!

    
    // Use a getter so we always get the *current* active player from the service
    val player: Player
        get() = service.player

    // Keep track of the player we are currently listening to
    private var internalPlayer: Player? = null
    
    val scope = binder.viewModelScope

    val playbackState = MutableStateFlow(player.playbackState)
    private val playWhenReady = MutableStateFlow(player.playWhenReady)
    val isPlaying = combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
        playWhenReady && playbackState != STATE_ENDED
    }.stateIn(scope, SharingStarted.Lazily, player.playWhenReady && player.playbackState != STATE_ENDED)
    val waitingForNetworkConnection: StateFlow<Boolean> = service.waitingForNetworkConnection.asStateFlow()

    // --- NEW: Expose Logical State ---
    val logicalState = service.logicalState
    // ---------------------------------

    val mediaMetadata = MutableStateFlow(player.currentMetadata)
    val currentSong = mediaMetadata.flatMapLatest {
        database.song(it?.id)
    }
    val currentLyrics: Flow<SemanticLyrics> = mediaMetadata.flatMapLatest { mediaMetadata ->
        if (mediaMetadata != null) {
            return@flatMapLatest flowOf(service.lyricsHelper.getLyrics(mediaMetadata) ?: uninitializedLyric)
        } else {
            return@flatMapLatest flowOf()
        }
    }

    private val currentMediaItemIndex = MutableStateFlow(-1)

    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())

    var queuePlaylistId = MutableStateFlow<String?>(null)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)

    init {
        // Observe the active player from MusicService
        scope.launch {
            service.activePlayer.collect { newPlayer ->
                if (newPlayer != null && newPlayer != internalPlayer) {
                    internalPlayer?.removeListener(this@PlayerConnection)
                    internalPlayer = newPlayer
                    newPlayer.addListener(this@PlayerConnection)
                    
                    Log.d(TAG, "PlayerConnection switched to new player: $newPlayer")

                    // Sync state immediately
                    playbackState.value = newPlayer.playbackState
                    playWhenReady.value = newPlayer.playWhenReady
                    shuffleModeEnabled.value = newPlayer.shuffleModeEnabled
                    repeatMode.value = newPlayer.repeatMode
                    mediaMetadata.value = newPlayer.currentMetadata
                    
                    updateCanSkipPreviousAndNext()
                    
                    // Queue info
                    queueWindows.value = newPlayer.getQueueWindows()
                    // currentMediaItemIndex.value = newPlayer.currentMediaItemIndex // DON'T USE PLAYER INDEX (Decks = 0)
                    currentWindowIndex.value = newPlayer.getCurrentQueueIndex()
                    
                    error.value = newPlayer.playerError
                }
            }
        }
        
        // Bind logical index
        scope.launch {
            service.logicalIndex.collect {
                currentMediaItemIndex.value = it
            }
        }
    
        // Initial sync 
         queuePlaylistId.value = service.queuePlaylistId
         currentMediaItemIndex.value = service.queueBoard.getCurrentQueue()?.queuePos ?: 0
    }

    fun playQueue(
        queue: Queue,
        shouldResume: Boolean = false,
        replace: Boolean = true,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        service.playQueue(
            queue = queue,
            shouldResume = shouldResume,
            replace = replace,
            title = title,
            isRadio = isRadio
        )
    }

    fun seekToLogical(positionMs: Long) {
        service.seekToLogical(positionMs)
    }

    /**
     * Add item to queue, right after current playing item
     */
    fun enqueueNext(item: MediaItem) = enqueueNext(listOf(item))

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        service.enqueueNext(items)
    }

    /**
     * Add item to end of current queue
     */
    fun enqueueEnd(item: MediaItem) = enqueueEnd(listOf(item))

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        service.enqueueEnd(items)
    }

    fun toggleLike() {
        service.toggleLike()
    }

    fun toggleLibrary() {
        service.toggleLibrary()
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        error.value = player.playerError
    }

    override fun onPlayWhenReadyChanged(newPlayWhenReady: Boolean, reason: Int) {
        playWhenReady.value = newPlayWhenReady
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaMetadata.value = mediaItem?.metadata
        // currentMediaItemIndex.value = player.currentMediaItemIndex // Controlled by service.logicalIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        queueWindows.value = player.getQueueWindows()
        queuePlaylistId.value = service.queuePlaylistId
        // currentMediaItemIndex.value = player.currentMediaItemIndex // Controlled by service.logicalIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    /**
     * Shuffles the queue
     */
    fun triggerShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            reportException(playbackError)
        }
        error.value = playbackError
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    || !window.isLive()
                    || player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive() && window.isDynamic
                    || player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        player.removeListener(this)
    }

    fun softKillPlayer() {
        Log.i(TAG, "Stopping player and uninitializing queue")
        player.clearMediaItems()
        service.deInitQueue()
    }
}
