/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioDecoderKey
import com.dd3boh.outertune.constants.AudioGaplessOffloadKey
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioOffloadKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.MAX_PLAYER_CONSECUTIVE_ERR
import com.dd3boh.outertune.constants.MaxQueuesKey
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleLike
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleShuffle
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleStartRadio
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.PauseRemoteListenHistoryKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PlayerVolumeKey
import com.dd3boh.outertune.constants.RepeatModeKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.daos.TransitionDao
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.db.entities.TransitionEntity
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.extensions.SilentHandler
import com.dd3boh.outertune.extensions.collect
import com.dd3boh.outertune.extensions.collectLatest
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.findNextMediaItemById
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.setOffloadEnabled
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.lyrics.LyricsHelper
import com.dd3boh.outertune.models.LogicalPlayerState
import com.dd3boh.outertune.models.HybridCacheDataSinkFactory
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.utils.CoilBitmapLoader
import com.dd3boh.outertune.utils.NetworkConnectivityObserver
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.playerCoroutine
import com.dd3boh.outertune.utils.reportException
import com.google.common.util.concurrent.MoreExecutors
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.db.entities.SongEntity
import dagger.hilt.android.AndroidEntryPoint
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    val TAG = "MusicServiceDebug"
    private var lastLogTime = 0L

    @Inject
    lateinit var database: MusicDatabase
    private val scope = CoroutineScope(Dispatchers.Main)
    private val offloadScope = CoroutineScope(playerCoroutine)

    // Critical player components
    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private val binder = MusicBinder()
    private lateinit var connectivityManager: ConnectivityManager

    val qbInit = MutableStateFlow(false)
    var queueBoard = QueueBoard(this, maxQueues = 1)
    var queuePlaylistId: String? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var deckManager: DeckManager
    // Helper property to keep existing code working (points to currently hearing player)
    val player: ExoPlayer
        get() = deckManager.activeDeck

    private lateinit var mediaSession: MediaLibrarySession

    // Player components
    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var transitionDao: TransitionDao

    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(true)

    lateinit var sleepTimer: SleepTimer

    // Player vars
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)

    // --- NEW: Logical State for Gapless UI ---
    private val _logicalState = MutableStateFlow(LogicalPlayerState())
    val logicalState = _logicalState.asStateFlow()

    private val _activePlayer = MutableStateFlow<ExoPlayer?>(null)
    val activePlayer = _activePlayer.asStateFlow()

    private val _logicalIndex = MutableStateFlow(0)
    val logicalIndex = _logicalIndex.asStateFlow()

    // Cache the transition for the CURRENT song to avoid DB hits every tick
    private var currentTransitionCache: TransitionEntity? = null

    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(offloadScope, SharingStarted.Lazily, null)

    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    private val normalizeFactor = MutableStateFlow(1f)

    private val audioDecoder = dataStore.get(AudioDecoderKey, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    private val isGaplessOffloadAllowed = dataStore.get(AudioGaplessOffloadKey, false)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    private var isAudioEffectSessionOpened = false

    var consecutivePlaybackErr = 0

    override fun onCreate() {
        Log.i(TAG, "Starting MusicService")
        super.onCreate()

        deckManager = DeckManager(this, ::createDataSourceFactory) { newActivePlayer ->
            Log.e(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.e(TAG, "║  onActiveDeckChanged CALLBACK                              ║")
            Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
            Log.e(TAG, "  New Active Player: ${if (newActivePlayer == deckManager.playerA) "A" else "B"}")
            Log.e(TAG, "  Old MediaSession Player: ${if (mediaSession.player == deckManager.playerA) "A" else "B"}")

            // Update MediaSession to point to the new active player
            mediaSession.player = newActivePlayer
            _activePlayer.value = newActivePlayer // UPDATE FLOW
            Log.e(TAG, "  → MediaSession.player updated")

            // NOW advance the queue since the physical transition is complete
            // NOW advance the queue since the physical transition is complete
            // Note: newActivePlayer.currentMediaItemIndex is likely 0 if using Decks with single items.
            // We must advance the logical queue board position.
            val currentQ = queueBoard.getCurrentQueue()
            val nextIndex = if (currentQ != null) currentQ.queuePos + 1 else -1

            if (currentQ != null) {
                Log.e(TAG, "  → Advancing queue from index ${currentQ.queuePos} to $nextIndex")
                queueBoard.setCurrQueuePosIndex(nextIndex)
                _logicalIndex.value = nextIndex
            } else {
                 Log.e(TAG, "  → Current queue is null, cannot advance index")
            }
            Log.e(TAG, "  → Queue advanced")

            // Update current metadata
            currentMediaMetadata.value = newActivePlayer.currentMetadata
            Log.e(TAG, "  → currentMediaMetadata updated to: ${newActivePlayer.currentMetadata?.title}")

            // Pre-warm the 'new' standby deck for the NEXT transition
            val transition = currentTransitionCache
            val songAfterNext = queueBoard.getSongAtIndex(nextIndex + 1)

            Log.e(TAG, "  Checking if we should pre-warm next song...")
            Log.e(TAG, "    Transition: ${if (transition != null) "YES" else "NULL"}")
            Log.e(TAG, "    Song After Next: ${songAfterNext?.title ?: "NULL"}")

            if (transition != null && songAfterNext != null && transition.toSongId == songAfterNext.id) {
                Log.e(TAG, "  → Pre-warming standby for: ${songAfterNext.title}")
                val mediaItem = songAfterNext.toMediaItem()
                deckManager.prepareNext(mediaItem, transition.entryPointMs, null)
            } else {
                Log.e(TAG, "  → No pre-warm needed")
            }

            // Reset the logical transition state
            _logicalState.value = _logicalState.value.copy(isTransitionActive = false)
            Log.e(TAG, "  → Logical transition state reset")
            Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
        }
        
        // Initialize Flow
        _activePlayer.value = deckManager.activeDeck

        // Attach listeners to DeckManager (which attaches to both A and B)
        deckManager.addListener(this)

        sleepTimer = SleepTimer(scope, player)
        deckManager.addListener(sleepTimer)

//        player = ExoPlayer.Builder(this)
//            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
//            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
//            .setHandleAudioBecomingNoisy(true)
//            .setWakeMode(C.WAKE_MODE_NETWORK)
//            .setAudioAttributes(
//                AudioAttributes.Builder()
//                    .setUsage(C.USAGE_MEDIA)
//                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
//                    .build(), true
//            )
//            .setSeekBackIncrementMs(5000)
//            .setSeekForwardIncrementMs(5000)
//            .build()
//            .apply {
//                // listeners
//                addListener(this@MusicService)
//                sleepTimer = SleepTimer(scope, this)
//                addListener(sleepTimer)
//                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
//
//                // misc
//                setOffloadEnabled(dataStore.get(AudioOffloadKey, false))
//            }
//
        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }

        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // TODO: do i even want to have smaller art for media notification
            .setBitmapLoader(CoilBitmapLoader(this))
            .build()

        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        currentSong.collect(scope) {
            updateNotification()
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this@MusicService,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )

        // lateinit tasks
        offloadScope.launch {
            Log.i(TAG, "Launching MusicService offloadScope tasks")
            if (!qbInit.value) {
                initQueue()
            }

            combine(playerVolume, normalizeFactor, deckManager.isCrossfading) { playerVolume, normalizeFactor, isCrossing ->
                Triple(playerVolume, normalizeFactor, isCrossing)
            }.collectLatest(scope) { (playerVolume, normalizeFactor, isCrossing) ->
                withContext(Dispatchers.Main) {
                    if (!isCrossing) {
                        player.volume = playerVolume * normalizeFactor
                    }
                }
            }

            playerVolume.debounce(1000).collect(scope) { volume ->
                dataStore.edit { settings ->
                    settings[PlayerVolumeKey] = volume
                }
            }

            dataStore.data
                .map { it[SkipSilenceKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) {
                    withContext(Dispatchers.Main) {
                        player.skipSilenceEnabled = it
                    }
                }

            combine(
                currentFormat,
                dataStore.data
                    .map { it[AudioNormalizationKey] ?: true }
                    .distinctUntilChanged()
            ) { format, normalizeAudio ->
                format to normalizeAudio
            }.collectLatest(scope) { (format, normalizeAudio) ->
                normalizeFactor.value = if (normalizeAudio && format?.loudnessDb != null) {
                    min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
                } else {
                    // Safe default: 0.5f (-6dB) to prevent volume bursts while loading format
                    0.5f
                }
            }


            // network connectivity
            try {
                connectivityObserver.unregister()
            } catch (e: UninitializedPropertyAccessException) {
                // lol
            }
            connectivityObserver = NetworkConnectivityObserver(this@MusicService)

            offloadScope.launch {
                connectivityObserver.networkStatus.collect { isConnected ->
                    isNetworkConnected.value = isConnected

                    if (isConnected && waitingForNetworkConnection.value) {
                        waitingForNetworkConnection.value = false
                        withContext(Dispatchers.Main) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
        }
        startMixPoller()
    }


// Library functions

    private fun createExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), false
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                // Note: We don't add listeners here anymore,
                // we add them to DeckManager in onCreate
                setOffloadEnabled(dataStore.get(AudioOffloadKey, false))
            }
    }

    private suspend fun recoverSong(mediaId: String, playbackData: YTPlayerUtils.PlaybackData? = null) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint = YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                val song = it.song.toggleLike()
                update(song)

                if (!song.isLocal) {
                    syncUtils.likeSong(song)
                }
            }
        }
    }

    fun toggleStartRadio() {
        val mediaMetadata = player.currentMetadata ?: return
        playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
    }


// Queue

    /**
     * Play a queue.
     *
     * @param queue Queue to play.
     * @param playWhenReady
     * @param shouldResume Set to true for the player should resume playing at the current song's last save position or
     * false to start from the beginning.
     * @param replace Replace media items instead of the underlying logic
     * @param title Title override for the queue. If this value us unspecified, this method takes the value from queue.
     * If both are unspecified, the title will default to "Queue".
     */
    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        shouldResume: Boolean = false,
        replace: Boolean = false,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        if (!qbInit.value) {
            runBlocking(Dispatchers.IO) {
                initQueue()
            }
        }

        var queueTitle = title
        queuePlaylistId = queue.playlistId
        var q: MultiQueueObject? = null
        val preloadItem = queue.preloadItem
        // do not use scope.launch ... it breaks randomly... why is this bug back???
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "playQueue: Resolving additional queue data...")
            try {
                if (preloadItem != null) {
                    q = queueBoard.addQueue(
                        queueTitle ?: "Radio\u2060temp",
                        listOf(preloadItem),
                        shuffled = queue.startShuffled,
                        replace = replace,
                        continuationEndpoint = null // fulfilled later on after initial status
                    )
                    queueBoard.setCurrQueue(q, true)
                }

                val initialStatus = withContext(Dispatchers.IO) { queue.getInitialStatus() }
                // do not find a title if an override is provided
                if ((title == null) && initialStatus.title != null) {
                    queueTitle = initialStatus.title

                    if (preloadItem != null && q != null) {
                        queueBoard.renameQueue(q!!, queueTitle)
                    }
                }

                val items = ArrayList<MediaMetadata>()
                Log.d(TAG, "playQueue: Queue initial status item count: ${initialStatus.items.size}")
                if (!initialStatus.items.isEmpty()) {
                    if (preloadItem != null) {
                        items.add(preloadItem)
                        items.addAll(initialStatus.items.subList(1, initialStatus.items.size))
                    } else {
                        items.addAll(initialStatus.items)
                    }
                    val q = queueBoard.addQueue(
                        queueTitle ?: getString(R.string.queue),
                        items,
                        shuffled = queue.startShuffled,
                        startIndex = if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0,
                        replace = replace || preloadItem != null,
                        continuationEndpoint = if (isRadio) items.takeLast(4).shuffled().first().id else null // yq?.getContinuationEndpoint()
                    )
                    queueBoard.setCurrQueue(q, shouldResume)
                }

                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (e: Exception) {
                reportException(e)
                Toast.makeText(this@MusicService, "plr: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }

            Log.d(TAG, "playQueue: Queue additional data resolution complete")
        }
    }

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        scope.launch {
            if (!qbInit.value) {

                // when enqueuing next when player isn't active, play as a new song
                if (items.isNotEmpty()) {
                    playQueue(
                        ListQueue(
                            title = items.first().mediaMetadata.title.toString(),
                            items = items.mapNotNull { it.metadata }
                        )
                    )
                }
            } else {
                // enqueue next
                queueBoard.getCurrentQueue()?.let {
                    queueBoard.addSongsToQueue(it, player.currentMediaItemIndex + 1, items.mapNotNull { it.metadata })
                }
            }
        }
    }

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        queueBoard.enqueueEnd(items.mapNotNull { it.metadata })
    }

    fun triggerShuffle() {
        val oldIndex = player.currentMediaItemIndex
        queueBoard.setCurrQueuePosIndex(oldIndex)
        val currentQueue = queueBoard.getCurrentQueue() ?: return

        // shuffle and update player playlist
        if (!currentQueue.shuffled) {
            queueBoard.shuffleCurrent()
        } else {
            queueBoard.unShuffleCurrent()
        }
        queueBoard.setCurrQueue()

        updateNotification()
    }

    suspend fun initQueue() {
        Log.i(TAG, "+initQueue()")
        val persistQueue = dataStore.get(PersistentQueueKey, true)
        val maxQueues = dataStore.get(MaxQueuesKey, 19)
        if (persistQueue) {
            queueBoard = QueueBoard(this, queueBoard.masterQueues, database.readQueue().toMutableList(), maxQueues)
        } else {
            queueBoard = QueueBoard(this, queueBoard.masterQueues, maxQueues = maxQueues)
        }
        Log.d(TAG, "Queue with $maxQueues queue limit. Persist queue = $persistQueue. Queues loaded = ${queueBoard.masterQueues.size}")
        qbInit.value = true
        Log.i(TAG, "-initQueue()")
    }

    fun deInitQueue() {
        Log.i(TAG, "+deInitQueue()")
        val pos = player.currentPosition
        queueBoard.shutdown()
        if (dataStore.get(PersistentQueueKey, true)) {
            runBlocking(Dispatchers.IO) {
                saveQueueToDisk(pos)
            }
        }
        // do not replace the object. Can lead to entire queue being deleted even though it is supposed to be saved already
        qbInit.value = false
        Log.i(TAG, "-deInitQueue()")
    }

    suspend fun saveQueueToDisk(currentPosition: Long) {
        val data = queueBoard.getAllQueues()
        data.last().lastSongPos = currentPosition
        database.updateAllQueues(data)
    }


// Audio playback

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    private fun createCacheDataSource(): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    .build()
                            )
                        )
                    )
                    .setCacheWriteDataSinkFactory(
                        HybridCacheDataSinkFactory(playerCache) { dataSpec ->
                            val isLocal = queueBoard.getCurrentQueue()?.findSong(dataSpec.key ?: "")?.isLocal == true
                            Log.d(TAG, "SONG CACHE: ${!isLocal}")
                            !isLocal
                        }
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun createDataSourceFactory(): DataSource.Factory {
        val songUrlCache = HashMap<String, Pair<String, Long>>()
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            Log.d(TAG, "PLAYING: song id = $mediaId")

            var song = queueBoard.getCurrentQueue()?.findSong(dataSpec.key ?: "")
            if (song == null) { // in the case of resumption, queueBoard may not be ready yet
                song = runBlocking { database.song(dataSpec.key).first()?.toMediaMetadata() }
            }
            // local song
            if (song?.localPath != null) {
                if (song.isLocal) {
                    Log.d(TAG, "PLAYING: local song")
                    val file = File(song.localPath)
                    if (!file.exists()) {
                        throw PlaybackException(
                            "File not found",
                            Throwable(),
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }

                    return@Factory dataSpec.withUri(file.toUri())
                } else {
                    val isDownloadNew = downloadUtil.localMgr.getFilePathIfExists(mediaId)
                    isDownloadNew?.let {
                        Log.d(TAG, "PLAYING: Custom downloaded song")
                        return@Factory dataSpec.withUri(it)
                    }
                }
            }

            val isDownload =
                downloadCache.isCached(mediaId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)
            val isCache = playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
            if (isDownload || isCache) {
                Log.d(TAG, "PLAYING: remote song (cache = ${isCache}, download = ${isDownload})")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                Log.d(TAG, "PLAYING: remote song (temp cache)")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            Log.d(TAG, "PLAYING: remote song (online fetch)")

            val playbackData = runBlocking(Dispatchers.IO) {
                val audioQuality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                )
            }.getOrElse { throwable ->
                when (throwable) {
                    is PlaybackException -> throw throwable

                    is ConnectException, is UnknownHostException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is SocketTimeoutException -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }
            offloadScope.launch { recoverSong(mediaId, playbackData) }

            val streamUrl = playbackData.streamUrl

            songUrlCache[mediaId] =
                streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
            dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
        }
    }

    private fun createRenderersFactory(gaplessOffloadAllowed: Boolean): DefaultRenderersFactory {
        if (ENABLE_FFMETADATAEX) {
            return object : NextRenderersFactory(this@MusicService) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                }
            }
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(audioDecoder)
        } else {
            return object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                }
            }
        }
    }

    private var currentTransitionPlan: TransitionPlan? = null
    private var currentTransitionConfig: TransitionConfig? = null



    private fun loadGrid(song: SongEntity, rangeStart: Long? = null, rangeEnd: Long? = null): List<Double>? {
        // STRICT MODE: No fallbacks.
        if (song.beatGridPath != null) {
            try {
                // AudioDecoder.loadBeatGrid now returns List<Float>?
                // We pass the range to AudioDecoder to optimize filtering
                val loaded = com.dd3boh.outertune.utils.analysis.AudioDecoder.loadBeatGrid(java.io.File(song.beatGridPath), rangeStart, rangeEnd)
                if (loaded != null && loaded.isNotEmpty()) {
                    val result = loaded.map { it.toDouble() / 1000.0 }
                    
                    if (result.isNotEmpty()) {
                        Log.d(TAG, "STRICT: Loaded ${result.size} beats for ${song.title}. " +
                                "Range: ${result.first()}s to ${result.last()}s. " +
                                "Duration covered: ${result.last() - result.first()}s")
                    }
                    return result
                } else {
                    Log.e(TAG, "STRICT: BeatGrid file existed but returned null/empty. Failing.")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "STRICT: Exception loading BeatGrid. Failing.", e)
                return null
            }
        }
        
        // If no file path, try simple grid, but that too is strict now.
        return generateSimpleGrid(song)
    }

    private fun generateSimpleGrid(song: SongEntity): List<Double>? {
        val bpm = song.displayBpm ?: 0f
        val firstBeat = (song.firstBeatMs ?: 0L) / 1000.0
        val beatDur = 60.0 / bpm

        // STRICT: No fabricated numbers.
        if (bpm <= 0.1f) {
             Log.e(TAG, "STRICT: Invalid BPM ($bpm) for ${song.title}. Cannot generate grid.")
             return null
        }

        val dbDuration = if (song.duration > 0) song.duration.toDouble() else 0.0
        
        if (dbDuration <= 0.1) {
             Log.e(TAG, "STRICT: Invalid Duration ($dbDuration) for ${song.title}. Cannot generate grid.")
             return null
        }

        val grid = mutableListOf<Double>()
        var t = firstBeat
        // Safety check to prevent infinite loops
        if (beatDur <= 0.0) return null

        while (t < dbDuration) {
            grid.add(t)
            t += beatDur
        }
        
        if (grid.isEmpty()) {
            Log.e(TAG, "STRICT: Generated grid was empty.")
            return null
        }
        
        return grid
    }


    private fun startMixPoller() {
        offloadScope.launch {
            var lastLogTime = 0L
            while (isActive) {
                withContext(Dispatchers.Main) {
                    if (player.isPlaying) {
                        // 1. Update the UI Clock (NEW)
                        updateLogicalState()

                        // 2. Check for Transition Triggers (EXISTING)
                        checkMixStatus()
                        
                        if (System.currentTimeMillis() - lastLogTime > 3000) {
                            val trans = currentTransitionCache
                            Log.d(TAG, "MixPoller [3s]: Pos=${player.currentPosition}ms / ${player.duration}ms. " +
                                    "LogStateTransActive=${_logicalState.value.isTransitionActive}. " +
                                    "Cache=${if (trans != null) "YES (Exit=${trans.exitPointMs}, To=${trans.toSongId})" else "NULL"}")
                            lastLogTime = System.currentTimeMillis()
                        }
                    }
                }
                delay(20) // 20ms = 50 업데이트 per second (Low latency)
            }
        }
    }

    private var nextSongPreparedId: String? = null // Track what we prepared

    // Track if we already triggered the PLL for this transition
    private var isPllTriggered = false

    private suspend fun checkMixStatus() {
        val currentPosition = player.currentPosition
        val transition = currentTransitionCache

        // Detailed status every 3 seconds (already exists, but enhance it)
        if (System.currentTimeMillis() - lastLogTime > 3000) {
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            Log.d(TAG, "MixPoller Status Check:")
            Log.d(TAG, "  Current Pos: ${currentPosition}ms / ${player.duration}ms")
            Log.d(TAG, "  Transition Cache: ${if (transition != null) "YES" else "NULL"}")
            if (transition != null) {
                Log.d(TAG, "    Exit Point: ${transition.exitPointMs}ms")
                Log.d(TAG, "    Entry Point: ${transition.entryPointMs}ms")
                Log.d(TAG, "    Duration: ${transition.durationMs}ms")
                Log.d(TAG, "    To Song: ${transition.toSongId}")
            }
            Log.d(TAG, "  Logical State:")
            Log.d(TAG, "    Active Song: ${_logicalState.value.activeMetadata?.title}")
            Log.d(TAG, "    Is Transitioning: ${_logicalState.value.isTransitionActive}")
            Log.d(TAG, "  PLL Triggered: $isPllTriggered")
            Log.d(TAG, "  DeckManager isCrossfading: ${deckManager.isCrossfading.value}")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            lastLogTime = System.currentTimeMillis()
        }

        // 0. Prevent double-triggering
        if (_logicalState.value.isTransitionActive) return

        // 1. Basic Checks
        if (!player.isPlaying) return
        if (transition == null) return

        // 2. Validate Data Integrity
        val realDuration = player.duration
        if (realDuration > 0 && transition.exitPointMs > (realDuration - 500)) {
            Log.w(TAG, "⚠️ Bad Transition Data! ExitPoint: ${transition.exitPointMs} > Duration: $realDuration")
            return
        }

        // 3. Validate Queue Integrity
        val nextSong = queueBoard.peekNext()
        if (nextSong == null || nextSong.id != transition.toSongId) {
            Log.w(TAG, "⚠️ Queue Changed! Expected: ${transition.toSongId}, Got: ${nextSong?.id}")
            loadTransitionOnce(player.currentMediaItem?.mediaId ?: "", nextSong?.id)
            return
        }

        // 4. Handle Repeat Mode
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return

        // === 5. LAZY LOADING (15s Prior) ===
        // If we have a transition but data isn't loaded, check if we should load it now.
        if (!isTransitionDataLoaded && !isPllTriggered && transition.exitPointMs > 5000) {
            val loadTrigger = transition.exitPointMs - 15000 // 15s before
            if (currentPosition >= loadTrigger) {
                Log.d(TAG, "Late-loading Transition Data (15s prior)...")
                val currentId = player.currentMediaItem?.mediaId
                val nextId = nextSong.id
                
                if (currentId != null) {
                    val songA = database.song(currentId).firstOrNull()?.song
                    val songB = database.song(nextId).firstOrNull()?.song
                    
                    if (songA != null && songB != null) {
                         val exitSec = transition.exitPointMs / 1000.0
                         val entrySec = transition.entryPointMs / 1000.0
                         val durSec = transition.durationMs / 1000.0
                         val preroll = 5.0
                         
                         // Load Partial Grids
                         // Grid A: Exit - 10s to Exit + Duration + 5s
                         val startA = ((exitSec - 10.0).coerceAtLeast(0.0) * 1000).toLong()
                         val endA = ((exitSec + durSec + preroll) * 1000).toLong()
                         
                         // Grid B: Entry - 5s to Entry + Duration + 10s
                         val startB = ((entrySec - preroll).coerceAtLeast(0.0) * 1000).toLong()
                         val endB = ((entrySec + durSec + 10.0) * 1000).toLong()
                    
                         val (gridA, gridB) = withContext(Dispatchers.IO) {
                             val gA = loadGrid(songA, startA, endA)
                             val gB = loadGrid(songB, startB, endB)
                             gA to gB
                         }

                         val bpmA = songA.displayBpm ?: 0f
                         val bpmB = songB.displayBpm ?: 0f

                         if (gridA != null && gridB != null && bpmA > 0 && bpmB > 0) {
                             currentTransitionConfig = TransitionConfig(
                                 overlapMode = transition.overlapMode,
                                 eqMode = transition.eqMode,
                                 effectMode = transition.effectMode,
                                 barsCount = (transition.durationBeats ?: 32) / 4
                             )
                             
                             // Re-calc anchors based on PARTIAL grid
                             val exitBeatA = TransitionMath.getBeatForTimestamp(gridA, exitSec)
                             val entryBeatB = TransitionMath.getBeatForTimestamp(gridB, entrySec)

                             currentTransitionPlan = TransitionPlan(
                                 initialSpeedB = (if (transition.syncTempo && bpmB > 0) bpmA / bpmB else 1f).toDouble(),
                                 gridScalarB = 1.0,
                                 anchorBeatA = exitBeatA,
                                 anchorBeatB = entryBeatB,
                                 transitionDurationBeats = (durSec * (bpmA/60.0)).toDouble(), 
                                 exitPointMs = transition.exitPointMs,
                                 entryPointMs = transition.entryPointMs,
                                 durationMs = transition.durationMs,
                                 gridA = gridA,
                                 gridB = gridB
                             )
                             isTransitionDataLoaded = true
                             Log.d(TAG, "Lazy loading complete. Plan ready.")
                         } else {
                             Log.w(TAG, "Lazy loading failed (missing grids or BPM).")
                             // Don't try again repeatedly
                             isTransitionDataLoaded = true 
                         }
                    }
                }
            }
        }

        // === EXECUTION PHASE (PLL Trigger) ===
        val triggerTime = transition.exitPointMs - 3000

        if (currentPosition >= triggerTime && !isPllTriggered) {
            val plan = currentTransitionPlan
            val config = currentTransitionConfig

            if (plan != null && config != null) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "╔════════════════════════════════════════════════════════════╗")
                    Log.e(TAG, "║  TRIGGERING PLL TRANSITION NOW!                            ║")
                    Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
                    Log.e(TAG, "  Current Position: $currentPosition")
                    Log.e(TAG, "  Trigger Time: $triggerTime")
                    Log.e(TAG, "  Exit Point: ${transition.exitPointMs}")
                    Log.e(TAG, "  Next Song: ${nextSong.title}")

                    isPllTriggered = true

                    deckManager.startPllTransition(plan, transition.durationMs, config)

                    Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
                }
            } else {
                Log.e(TAG, "⚠️ Cannot trigger PLL - Plan or Config is NULL!")
            }
        }

        // === LOGICAL SWITCH PHASE ===
        if (currentPosition >= transition.exitPointMs) {
            withContext(Dispatchers.Main) {
                if (_logicalState.value.activeMetadata?.id == nextSong.id) {
                    return@withContext
                }

                Log.w(TAG, "╔════════════════════════════════════════════════════════════╗")
                Log.w(TAG, "║  LOGICAL SWITCH TRIGGERED (UI Update)                      ║")
                Log.w(TAG, "╚════════════════════════════════════════════════════════════╝")
                Log.w(TAG, "  Switching UI to show: ${nextSong.title}")
                Log.w(TAG, "  NOTE: Physical player still on Song A!")
                Log.w(TAG, "  NOTE: Queue will advance in completeTransition()")

                // CAPTURE ON MAIN THREAD
                val nextIndex = player.currentMediaItemIndex + 1

                offloadScope.launch {
                    val songAfterNext = queueBoard.getSongAtIndex(nextIndex + 1)
                    loadTransitionOnce(nextSong.id, songAfterNext?.id)

                    withContext(Dispatchers.Main) {
                        _logicalState.value = LogicalPlayerState(
                            activeMetadata = nextSong,
                            currentPositionMs = 0L,
                            durationMs = currentTransitionCache?.exitPointMs ?: (nextSong.duration * 1000L),
                            isTransitionActive = true
                        )
                        isPllTriggered = false
                        Log.w(TAG, "  → Logical state updated, isPllTriggered reset")
                    }
                }
            }
        }
    }

    private fun updateLogicalState() {
        val currentMeta = player.currentMetadata ?: return
        val realPos = player.currentPosition
        val realDur = player.duration

        // 1. Determine Logical Duration
        // If there is a transition, the track "ends" for the UI at the Exit Point.
        val logicalDuration = currentTransitionCache?.exitPointMs ?: realDur

        // 2. Determine Logical Position
        // Clamp position to logical duration. This prevents the UI slider from
        // jumping past the end during the audio crossfade overlap.
        val logicalPos = min(realPos, logicalDuration)

        // 3. Update State
        if (_logicalState.value.activeMetadata?.id == currentMeta.id) {
            _logicalState.value = _logicalState.value.copy(
                currentPositionMs = logicalPos,
                durationMs = logicalDuration,
                // If we are just playing normally, ensure this is false
                isTransitionActive = false
            )
        } else if (_logicalState.value.isTransitionActive) {
            // We are in a transition. The UI is showing the incoming song (Song B).
            // We should update the position based on the standby player.
            val standbyPos = deckManager.standbyDeck.currentPosition
            val standbyDur = deckManager.standbyDeck.duration
            _logicalState.value = _logicalState.value.copy(
                currentPositionMs = standbyPos,
                durationMs = if (standbyDur > 0) standbyDur else _logicalState.value.durationMs
            )
        } else if (_logicalState.value.activeMetadata == null) {
            // Initial/Reset State
            _logicalState.value = LogicalPlayerState(
                activeMetadata = currentMeta,
                currentPositionMs = logicalPos,
                durationMs = logicalDuration
            )
        } else {
            // User changed song manually (or queue advanced normally without transition)
            // The active metadata ID changed, but we are not in a transition.
            _logicalState.value = LogicalPlayerState(
                activeMetadata = currentMeta,
                currentPositionMs = logicalPos,
                durationMs = logicalDuration,
                isTransitionActive = false
            )
        }
    }


// Misc

    fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(getString(if (queueBoard.getCurrentQueue()?.shuffled == true) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setSessionCommand(CommandToggleShuffle)
                    .setCustomIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off)
                    .build(),
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setCustomIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat_off
                            REPEAT_MODE_ONE -> R.drawable.repeat_one
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder(if (currentSong.value?.song?.liked == true) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build()
            )
        )
    }

    fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
        Toast.makeText(this@MusicService, getString(R.string.wait_to_reconnect), Toast.LENGTH_LONG).show()
    }

    fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_PLAYER_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()

            Toast.makeText(this@MusicService, getString(R.string.err_play_next_on_error), Toast.LENGTH_SHORT).show()
            return
        }

        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_too_many_errors), Toast.LENGTH_LONG).show()
        consecutivePlaybackErr = 0
    }

    fun stopOnError() {
        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_error), Toast.LENGTH_LONG).show()
    }


// Player overrides

    override fun onPlayerError(error: PlaybackException) {
        // IGNORE errors from Standby Deck
        if (deckManager.activeDeck.playerError == null) {
            Log.w(TAG, "onPlayerError: Ignoring error from Standby Deck: ${error.message}")
            return
        }

        super.onPlayerError(error)
        Log.e(TAG, "Player Error Occurred: ${error.errorCodeName} - ${error.message}", error)
        Toast.makeText(this, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()


        // wait for reconnection
        val isConnectionError = (error.cause?.cause is PlaybackException)
                && (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        if (dataStore.get(SkipOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }

        Toast.makeText(
            this@MusicService,
            "plr: ${error.message} (${error.errorCode}): ${error.cause?.message ?: ""} ",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // IGNORE events from standby deck
        if (player.isPlaying != isPlaying) return

        if (!isPlaying) {
            val pos = player.currentPosition
            val q = queueBoard.getCurrentQueue()
            q?.lastSongPos = pos
        }
        super.onIsPlayingChanged(isPlaying)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // FILTER: Ignore events from the standby deck (e.g. when pre-warming)
        // We only want to react to the Active Deck changing tracks.
        val activeMediaId = deckManager.activeDeck.currentMediaItem?.mediaId
        if (mediaItem?.mediaId != activeMediaId && activeMediaId != null) {
            Log.d(TAG, "Ignoring onMediaItemTransition from Standby Deck. Event=${mediaItem?.mediaId}, Active=$activeMediaId")
            return
        }

        super.onMediaItemTransition(mediaItem, reason)
        // +2 when and error happens, and -1 when transition. Thus when error, number increments by 1, else doesn't change
        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }

        if (player.isPlaying && reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            player.prepare()
            player.play()
        }

        // Auto load more songs
        val q = queueBoard.getCurrentQueue()
        val songCount = q?.getSize() ?: -1
        val playlistId = q?.playlistId
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            playlistId != null // aka "hasNext"
        ) {
            Log.d(TAG, "onMediaItemTransition: Triggering queue auto load more")
            scope.launch(SilentHandler) {
                val endpoint = playlistId // playlistId.substringBefore("\n")
                val continuation = null // playlistId.substringAfter("\n")
                val yq = YouTubeQueue(WatchEndpoint(endpoint, continuation))
                val mediaItems = yq.nextPage()
                q.playlistId = mediaItems.takeLast(4).shuffled().first().id // yq.getContinuationEndpoint()
                Log.d(TAG, "onMediaItemTransition: Got ${mediaItems.size} songs from radio")
                if (player.playbackState != STATE_IDLE && songCount > 1) { // initial radio loading is handled by playQueue()
                    queueBoard.enqueueEnd(mediaItems.drop(1))
                }
            }
        }

        queueBoard.setCurrQueuePosIndex(player.currentMediaItemIndex)

        // reshuffle queue when shuffle AND repeat all are enabled
        // no, when repeat mode is on, player does not "STATE_ENDED"
        if (player.currentMediaItemIndex == player.mediaItemCount - 1 &&
            (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            player.shuffleModeEnabled && player.repeatMode == REPEAT_MODE_ALL
        ) {
            scope.launch(SilentHandler) {
                // or else race condition: Assertions.checkArgument(eventTime.realtimeMs >= currentPlaybackStateStartTimeMs) fails in updatePlaybackState()
                delay(200)
                queueBoard.shuffleCurrent(player.mediaItemCount > 2)
                queueBoard.setCurrQueue()
            }
        }

        // --- NEW: Refresh Transition Cache ---
        mediaItem?.mediaId?.let { currentId ->
            // Apply cached normalization immediately to prevent loudness jump
            if (nextSongnormFactorCache != null && deckManager.activeDeck.currentMediaItem?.mediaId == currentId) {
                 Log.d(TAG, "Applying cached normalization factor: $nextSongnormFactorCache")
                 normalizeFactor.value = nextSongnormFactorCache!!
                 nextSongnormFactorCache = null
            }

            offloadScope.launch {
                val nextSong = queueBoard.peekNext() // This works now
                loadTransitionOnce(currentId, nextSong?.id)
            }
        }

        updateNotification() // also updates when queue changes
    }
    
    // --- Transition Monitoring ---
    private var loadJob: Job? = null
    private var isTransitionDataLoaded = false

    private fun loadTransitionOnce(currentId: String, nextId: String?) {
        loadJob?.cancel()
        currentTransitionCache = null // Clear old cache immediately
        currentTransitionPlan = null
        currentTransitionConfig = null
        isTransitionDataLoaded = false
        
        if (nextId == null) {
            Log.d(TAG, "loadTransitionOnce: Next song is null (EndOfQueue). Monitoring disabled.")
            return
        }

        Log.d(TAG, "loadTransitionOnce: Checking Transition for $currentId -> $nextId (One-time)")
        
        loadJob = offloadScope.launch {
             val transition = transitionDao.getTransition(currentId, nextId)
             
             currentTransitionCache = transition
             // Reset plan data
             currentTransitionPlan = null 
             currentTransitionConfig = null
             isTransitionDataLoaded = false

             if (transition != null) {
                Log.d(TAG, "Transition Found! Exit=${transition.exitPointMs}, Dur=${transition.durationMs}")

                // --- CHECK FILE EXISTENCE (Lightweight) ---
                val songA = database.song(currentId).firstOrNull()?.song
                val songB = database.song(nextId).firstOrNull()?.song
                
                if (songA != null && songB != null) {
                     val hasGridA = songA.beatGridPath != null && java.io.File(songA.beatGridPath).exists()
                     val hasGridB = songB.beatGridPath != null && java.io.File(songB.beatGridPath).exists()
                     
                     if (!hasGridA || !hasGridB) {
                          // Try Simple Grid?
                          if (songA.displayBpm == null || songB.displayBpm == null) {
                              Log.w(TAG, "Transition exists but missing BeatGrids/BPM. Optimistic monitoring.")
                          }
                     }
                }

                // Pre-warm the secondary deck (player only, no beats yet)
                val nextSong = queueBoard.peekNext() 
                
                if (nextSong != null && nextSong.id == nextId) {
                    val bpmA = songA?.displayBpm ?: 120f 
                    val bpmB = songB?.displayBpm ?: 120f
                    val speedRatio = if (transition.syncTempo && bpmB > 0) bpmA / bpmB else null
                    
                    // Pre-fetch normalization
                    val nextFormat = database.format(nextId).firstOrNull()
                    val nextNorm = if (nextFormat?.loudnessDb != null) {
                        min(10f.pow(-nextFormat.loudnessDb.toFloat() / 20), 1f)
                    } else 1f
                    nextSongnormFactorCache = nextNorm

                    val mediaItem = nextSong.toMediaItem()
                    
                    // Approximate start time (Entry Point - 3s) since we don't have accurate grid yet
                    val startMs = (transition.entryPointMs - 3000).coerceAtLeast(0)

                    withContext(Dispatchers.Main) {
                        if (!deckManager.standbyDeck.isPlaying) {
                            deckManager.prepareNext(mediaItem, startMs, speedRatio)
                            deckManager.setStandbyVolumeMultiplier(nextNorm)
                        }
                    }
                }
             } else {
                Log.d(TAG, "No transition found in DB.")
             }
        }
    }
    
    // Add this cache variable
    private var nextSongnormFactorCache: Float? = null

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        // IGNORE events from standby deck
        if (player.playbackState != playbackState) return

        if (playbackState == STATE_IDLE) {
            queuePlaylistId = null
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (player != deckManager.activeDeck) return

        if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
                if (!player.playWhenReady) {
                    waitingForNetworkConnection.value = false
                }
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
    }



    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        offloadScope.launch {
            val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            var minPlaybackDur = (dataStore.get(minPlaybackDurKey, 30).toFloat() / 100)
            // ensure within bounds
            if (minPlaybackDur >= 1f) {
                minPlaybackDur = 0.99f // Ehhh 99 is good enough to avoid any rounding errors
            } else if (minPlaybackDur < 0.01f) {
                minPlaybackDur = 0.01f // Still want "spam skipping" to not count as plays
            }

            val playRatio =
                playbackStats.totalPlayTimeMs.toFloat() / ((mediaItem.metadata?.duration?.times(1000)) ?: -1)
            Log.d(TAG, "Playback ratio: $playRatio Min threshold: $minPlaybackDur")
            if (playRatio >= minPlaybackDur && !dataStore.get(PauseListenHistoryKey, false)) {
                database.query {
                    incrementPlayCount(mediaItem.mediaId)
                    try {
                        insert(
                            Event(
                                songId = mediaItem.mediaId,
                                timestamp = LocalDateTime.now(),
                                playTime = playbackStats.totalPlayTimeMs
                            )
                        )
                    } catch (_: SQLException) {
                    }
                }

                // TODO: support playlist id
                val ytHist = mediaItem.metadata?.isLocal != true && !dataStore.get(PauseRemoteListenHistoryKey, false)
                Log.d(TAG, "Trying to register remote history: $ytHist")
                if (ytHist) {
                    val playbackUrl = YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                        .getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    Log.d(TAG, "Got playback url: $playbackUrl")
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    fun seekToLogical(newPositionMs: Long) {
        val state = _logicalState.value
        val logicalDuration = state.durationMs

        // 1. Clamp to Logical Duration
        // Ensure we don't seek past the exit point (transition start)
        val clampedPosition = if (logicalDuration > 0) {
            newPositionMs.coerceIn(0L, logicalDuration)
        } else {
            newPositionMs
        }

        // 2. Handle "Scrubbing backwards across boundary"
        // If the user was visually on "Song B" (transition active) but scrubs back to 0:00,
        // we are still effectively playing Song B (or about to).
        // Since we physically switch tracks at the transition point,
        // standard seeking usually works, BUT we must cancel any active crossfade.

        if (deckManager.activeDeck.isPlaying && state.isTransitionActive) {
            // Cancel any active crossfade if the user interrupts
            deckManager.cancelCrossfade()
            // Reset logical state to match physical reality
            // (The update loop will fix it next tick, but good to be explicit)
        }

        player.seekTo(clampedPosition)

        // Update state immediately for UI responsiveness (optimistic update)
        _logicalState.value = state.copy(currentPositionMs = clampedPosition)
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        offloadScope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val q = queueBoard.getCurrentQueue()
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(player.mediaItemCount))
        if (q == null || q.shuffled == shuffleModeEnabled) return
        triggerShuffle()
    }


    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // FG keep alive
        if (player.isPlaying || !dataStore.get(KeepAliveKey, false)) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Terminating MusicService.")
        deInitQueue()

        mediaSession.player.stop()
        mediaSession.release()
        mediaSession.player.release()
        deckManager.release()
        super.onDestroy()
        Log.i(TAG, "Terminated MusicService.")
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved called")
        if (dataStore.get(StopMusicOnTaskClearKey, true) && !dataStore.get(KeepAliveKey, false)) {
            Log.i(TAG, "onTaskRemoved kill")
            pauseAllPlayersAndStopSelf()
        } else {
            Log.i(TAG, "onTaskRemoved def")
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SEARCH = "search"

        const val CHANNEL_ID = "music_channel_01"
        const val CHANNEL_NAME = "fgs_workaround"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L

        const val COMMAND_GET_BINDER = "GET_BINDER"
    }
}
