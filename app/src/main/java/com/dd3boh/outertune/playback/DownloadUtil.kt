package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_DOWNLOADING
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_INVALID
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadEvent
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.InvalidAudioFileException
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    val TAG = DownloadUtil::class.simpleName.toString()

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()

    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        .build()
                )
            )
    ) { dataSpec ->
        return@Factory dataSpec
    }

    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            maxParallelDownloads = 3
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
                )
            )
        }

    val downloads = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())

    private fun getDefaultDownloadPath(): Uri {
        val file = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val uri = file?.toUri() ?: Uri.EMPTY
        Log.d(TAG, "Default download path: $uri (file: ${file?.absolutePath})")
        return uri
    }

    var localMgr: DownloadDirectoryManagerOt
    val downloadMgr: DownloadManagerOt
    var isProcessingDownloads = MutableStateFlow(false)

    init {
        Log.i(TAG, "=== DownloadUtil INIT START ===")

        val savedPath = context.dataStore.get(DownloadPathKey, "")
        Log.d(TAG, "Saved download path from DataStore: '$savedPath'")

        val dlUri = if (savedPath.isNotEmpty()) savedPath.toUri() else getDefaultDownloadPath()
        Log.i(TAG, "Using download URI: $dlUri")

        val extraUris = uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        Log.d(TAG, "Extra download URIs: ${extraUris.size} paths")

        localMgr = DownloadDirectoryManagerOt(context, dlUri, extraUris)

        // CREATE HTTP CLIENT WITH SAME CONFIG AS YOUTUBE
        val downloadHttpClient = OkHttpClient.Builder()
            .proxy(YouTube.proxy)  // Use YouTube's proxy if set
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "com.google.android.youtube/19.02.39 (Linux; U; Android 13) gzip")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-")
                    .build()
                chain.proceed(request)
            }
            .build()

        downloadMgr = DownloadManagerOt(localMgr, downloadHttpClient)

        Log.i(TAG, "Download managers initialized")

        // Observe custom download events to update Database and UI state
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Starting download event collector")
            downloadMgr.events.collect { event ->
                when (event) {
                    is DownloadEvent.Success -> {
                        Log.i(TAG, "=== DOWNLOAD SUCCESS ===")
                        Log.i(TAG, "Media ID: ${event.mediaId}")
                        Log.i(TAG, "File URI: ${event.file}")
                        Log.i(TAG, "File scheme: ${event.file.scheme}")

                        val timeNow = LocalDateTime.now()

                        // FIX: Get absolute path ensuring it starts with /
                        val path: String? = when {
                            event.file.scheme == "file" -> {
                                val rawPath = event.file.path
                                val fixedPath = if (rawPath?.startsWith("/") == true) rawPath else "/$rawPath"
                                Log.d(TAG, "File scheme path - Raw: '$rawPath', Fixed: '$fixedPath'")
                                fixedPath
                            }
                            else -> {
                                val file = fileFromUri(context, event.file)
                                val absPath = file?.absolutePath
                                Log.d(TAG, "Non-file scheme - Resolved to: '$absPath'")
                                absPath
                            }
                        }

                        if (path != null) {
                            Log.i(TAG, "Final resolved path: $path")

                            // Create Download entity instead of updating Song
                            val download = com.dd3boh.outertune.db.entities.Download(
                                songId = event.mediaId,
                                localPath = path,
                                downloadedAt = System.currentTimeMillis(),
                                analysisStatus = com.dd3boh.outertune.db.entities.AnalysisStatus.PENDING
                            )

                            try {
                                database.downloadDao().insertDownload(download)
                                Log.d(TAG, "Download entity inserted into database")

                                // Also update Song for backward compatibility
                                database.registerDownloadSong(event.mediaId, timeNow, path)
                                Log.d(TAG, "Song entity updated with download info")
                            } catch (e: Exception) {
                                Log.e(TAG, "Database update failed", e)
                                reportException(e)
                            }
                        } else {
                            Log.e(TAG, "!!! FAILED TO RESOLVE PATH !!!")
                            Log.e(TAG, "URI: ${event.file}")
                            Log.e(TAG, "Scheme: ${event.file.scheme}")
                        }

                        downloads.update { map ->
                            map.toMutableMap().apply { put(event.mediaId, timeNow) }
                        }
                        Log.i(TAG, "=== DOWNLOAD SUCCESS COMPLETE ===")
                    }

                    is DownloadEvent.Failure -> {
                        Log.e(TAG, "=== DOWNLOAD FAILED ===")
                        Log.e(TAG, "Media ID: ${event.mediaId}")
                        Log.e(TAG, "Error: ${event.error?.message}")
                        Log.e(TAG, "Error type: ${event.error?.javaClass?.simpleName}")
                        event.error?.printStackTrace()

                        database.updateDownloadStatus(event.mediaId, null)
                        downloads.update { map ->
                            map.toMutableMap().apply { remove(event.mediaId) }
                        }

                        withContext(Dispatchers.Main) {
                            val errorMsg = event.error?.message ?: "Unknown error"
                            Toast.makeText(context, "Download failed: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                        Log.e(TAG, "=== DOWNLOAD FAILED END ===")
                    }

//                    is DownloadEvent.Progress -> {
//                        Log.v(TAG, "Download progress: ${event.mediaId} - ${event.progress}%")
//                    }
                    // my actual code
                    else -> {}
                }
            }
        }

        CoroutineScope(dlCoroutine).launch {
            rescanDownloads()
        }

        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    Log.d(TAG, "DownloadManager.onDownloadChanged: ${download.request.id}, state=${download.state}")
                    if (finalException != null) {
                        Log.e(TAG, "Download exception for ${download.request.id}", finalException)
                    }

                    if (download.state == Download.STATE_COMPLETED) {
                        Log.i(TAG, "Download completed (ExoPlayer): ${download.request.id}")
                        downloads.update { map ->
                            if (!map.containsKey(download.request.id)) {
                                map + (download.request.id to stateToLocalDateTime(download))
                            } else map
                        }
                    }
                }
            }
        )

        Log.i(TAG, "=== DownloadUtil INIT COMPLETE ===")
    }

    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }

    fun download(songs: List<MediaMetadata>) {
        Log.i(TAG, "Downloading ${songs.size} songs")
        songs.forEach { song -> downloadSong(song.id, song.title) }
    }

    fun download(song: MediaMetadata) {
        Log.i(TAG, "Download requested: [${song.id}] ${song.title}")
        downloadSong(song.id, song.title)
    }

    fun download(song: SongEntity) {
        Log.i(TAG, "Download requested: [${song.id}] ${song.title}")
        downloadSong(song.id, song.title)
    }

    private fun downloadSong(id: String, title: String) {
        Log.i(TAG, "=== STARTING DOWNLOAD ===")
        Log.i(TAG, "Song ID: $id")
        Log.i(TAG, "Title: $title")

        if (downloads.value[id] != null) {
            Log.w(TAG, "Song already downloading or downloaded, skipping")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Updating download state to DOWNLOADING")
                downloads.update { it + (id to STATE_DOWNLOADING) }

                Log.d(TAG, "Fetching playback data...")
                Log.d(TAG, "Audio quality: $audioQuality")
                Log.d(TAG, "Network connected: ${connectivityManager.activeNetwork != null}")

                val playbackData = YTPlayerUtils.playerResponseForPlayback(
                    id,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                ).getOrThrow()

                Log.i(TAG, "Playback data obtained successfully")
                Log.d(TAG, "Stream URL length: ${playbackData.streamUrl.length}")
                Log.d(TAG, "Format itag: ${playbackData.format.itag}")
                Log.d(TAG, "Format bitrate: ${playbackData.format.bitrate}")

                val format = playbackData.format
                Log.d(TAG, "Upserting format entity to database")
                database.query {
                    upsert(
                        FormatEntity(
                            id = id,
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
                Log.d(TAG, "Format entity upserted")

                Log.i(TAG, "Enqueueing download to DownloadManagerOt")
                downloadMgr.enqueue(id, playbackData.streamUrl, title)
                Log.i(TAG, "Download enqueued successfully")

            } catch (e: Exception) {
                Log.e(TAG, "=== DOWNLOAD START FAILED ===", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Exception message: ${e.message}")
                e.printStackTrace()

                reportException(e)
                downloads.update { it - id }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to start download: ${e.message}", LENGTH_SHORT).show()
                }
            }
        }
    }

    fun resumeDownloadsOnStart() {
        Log.d(TAG, "Resuming downloads on start")
        DownloadService.sendResumeDownloads(
            context,
            ExoDownloadService::class.java,
            false
        )
    }

    fun delete(song: PlaylistSong) = deleteSong(song.song.id)
    fun delete(song: SongItem) = deleteSong(song.id)
    fun delete(song: Song) = deleteSong(song.song.id)
    fun delete(song: SongEntity) = deleteSong(song.id)
    fun delete(song: MediaMetadata) = deleteSong(song.id)

    private fun deleteSong(id: String): Boolean {
        Log.i(TAG, "Deleting song: $id")
        val deleted = localMgr.deleteFile(id)

        if (!deleted) {
            Log.w(TAG, "Failed to delete file for: $id")
            return false
        }

        Log.d(TAG, "File deleted successfully: $id")
        downloads.update { map ->
            map.toMutableMap().apply {
                remove(id)
            }
        }

        runBlocking {
            database.song(id).first()?.song?.copy(localPath = null)
            database.updateDownloadStatus(id, null)
            database.downloadDao().deleteDownload(id)
        }
        Log.i(TAG, "Delete completed: $id")
        return true
    }

    fun getFromCache(cache: SimpleCache, mediaId: String): ByteArray? {
        val spans: Set<CacheSpan> = cache.getCachedSpans(mediaId)
        if (spans.isEmpty()) return null

        val output = ByteArrayOutputStream()
        try {
            for (span in spans) {
                val file: File? = span.file
                FileInputStream(file).use { fis ->
                    fis.copyTo(output)
                }
            }
            return output.toByteArray()
        } catch (e: IOException) {
            reportException(e)
        } finally {
            output.close()
        }
        return null
    }

    suspend fun migrateDownloads() {
        if (isProcessingDownloads.value) return
        isProcessingDownloads.value = true

        var runs = 0
        try {
            val dataSourceFactory = ResolvingDataSource.Factory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient()))
            ) { it }

            val downloadManager = DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                Executor(Runnable::run)
            )

            val downloadedSongs = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                downloadedSongs[cursor.download.request.id] = cursor.download
            }

            val toMigrate = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED }
            toMigrate.forEach { s ->
                if (runs++ % 10 == 0) {
                    Log.d(TAG, "Migrating download: $runs/${toMigrate.size}")
                    if (runs % 20 == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "$runs/${toMigrate.size}", LENGTH_SHORT).show()
                        }
                    }
                }
                val songFromCache = getFromCache(downloadCache, s.key)
                if (songFromCache != null) {
                    downloadCache.removeResource(s.key)
                    downloadMgr.enqueue(
                        mediaId = s.key,
                        data = songFromCache,
                        displayName = runBlocking { database.song(s.key).first()?.title ?: "" })
                }
            }
            scanDownloads()
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isProcessingDownloads.value = false
        }
    }


    fun cd() {
        Log.d(TAG, "Changing download directory")
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        )
    }

    suspend fun rescanDownloads() {
        Log.i(TAG, "+rescanDownloads()")
        isProcessingDownloads.value = true
        val dbDownloads = database.downloadedOrQueuedSongs().first()
        val result = mutableMapOf<String, LocalDateTime>()

        val missingFiles =
            localMgr.getMissingFiles(dbDownloads.filterNot { it.song.dateDownload == null }).toMutableList()
        Log.d(TAG, "Found ${missingFiles.size}/${dbDownloads.size} songs not in custom download directories")
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            missingFiles.removeIf { it.id == cursor.download.request.id }
        }

        database.transaction {
            missingFiles.forEach {
                Log.v(TAG, "Shedding: [${it.id}] ${it.song.title}")
                removeDownloadSong(it.song.id)
                // Also remove from Download table
                runBlocking{
                    downloadDao().deleteDownload(it.song.id)
                }
            }
        }

        val availableDownloads = dbDownloads.minus(missingFiles)
        availableDownloads.forEach { s ->
            result[s.song.id] = s.song.dateDownload!!
        }

        downloads.value = result
        isProcessingDownloads.value = false
        Log.i(TAG, "-rescanDownloads()")
    }


    suspend fun scanDownloads() {
        Log.i(TAG, "+scanDownloads()")
        if (isProcessingDownloads.value) {
            Log.i(TAG, "-scanDownloads()")
            return
        }
        isProcessingDownloads.value = true

        database.removeAllDownloadedSongs()
        val timeNow = LocalDateTime.now()

        val availableFiles = localMgr.getAvailableFiles(false)
        database.transaction {
            availableFiles.forEach { f ->
                try {
                    //Ensure absolute path with leading slash
                    val path: String? = when {
                        f.value.scheme == "file" -> {
                            val rawPath = f.value.path
                            if (rawPath?.startsWith("/") == true) rawPath else "/$rawPath"
                        }
                        else -> fileFromUri(context, f.value)?.absolutePath
                    }

                    if (path != null) {
                        registerDownloadSong(f.key, timeNow, path)

                        // Create or update Download entity
                        runBlocking{
                            val existingDownload = downloadDao().getDownload(f.key)
                            if (existingDownload == null) {
                                val download = com.dd3boh.outertune.db.entities.Download(
                                    songId = f.key,
                                    localPath = path,
                                    downloadedAt = System.currentTimeMillis(),
                                    analysisStatus = com.dd3boh.outertune.db.entities.AnalysisStatus.PENDING
                                )
                                downloadDao().insertDownload(download)
                            }
                        }
                    }

                } catch (e: InvalidAudioFileException) {
                    reportException(e)
                }
            }
        }
        Log.d(TAG, "Registered ${availableFiles.size} files from custom downloads")

        val cursor = downloadManager.downloadIndex.getDownloads()
        var count = 0
        database.transaction {
            while (cursor.moveToNext()) {
                updateDownloadStatus(cursor.download.request.id, stateToLocalDateTime(cursor.download))
                count++
            }
        }
        Log.d(TAG, "Registered $count files from internal downloads")
        isProcessingDownloads.value = false
        rescanDownloads()
        Log.i(TAG, "-scanDownloads()")
    }


    companion object {
        val STATE_DOWNLOADING: LocalDateTime = Instant.ofEpochMilli(1).atZone(ZoneOffset.UTC).toLocalDateTime()
        val STATE_INVALID: LocalDateTime = Instant.ofEpochMilli(0).atZone(ZoneOffset.UTC).toLocalDateTime()
    }
}

fun stateToLocalDateTime(download: Download): LocalDateTime {
    return when (download.state) {
        Download.STATE_COMPLETED -> {
            Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
        }

        Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> STATE_DOWNLOADING
        else -> STATE_INVALID
    }
}

suspend fun MusicDatabase.registerDownloadSong(id: String, downloadDate: LocalDateTime, localPath: String?) {
    val song = song(id).first()?.song
    if (song != null) {
        query {
            update(song.copy(dateDownload = downloadDate, localPath = localPath))
        }
    }
}
