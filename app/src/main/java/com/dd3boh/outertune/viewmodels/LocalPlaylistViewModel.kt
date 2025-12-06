package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dd3boh.outertune.constants.PlaylistSongSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSongSortType
import com.dd3boh.outertune.constants.PlaylistSongSortTypeKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.utils.analysis.AnalysisWorker
import com.dd3boh.outertune.db.daos.DownloadDao
import com.dd3boh.outertune.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
    private val downloadDao: DownloadDao,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!

    private val workManager = WorkManager.getInstance(context)

    val playlistWithSongs = combine(
        database.playlist(playlistId),
        database.playlistSongs(playlistId),
        context.dataStore.data
            .map {
                it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM) to
                        (it[PlaylistSongSortDescendingKey] ?: true)
            }
            .distinctUntilChanged()
    ) { playlist, songs, (sortType, sortDescending) ->
        val sortedSongs = when (sortType) {
            PlaylistSongSortType.CUSTOM -> songs
            PlaylistSongSortType.NAME -> songs.sortedBy { it.song.song.title.lowercase() }
            PlaylistSongSortType.ARTIST -> songs.sortedBy { song ->
                song.song.artists.joinToString { it.name }.lowercase()
            }
            PlaylistSongSortType.ADDED_DATE -> songs.sortedBy { it.song.song.inLibrary }
            PlaylistSongSortType.MODIFIED_DATE -> songs.sortedBy { it.song.song.dateModified }
            PlaylistSongSortType.RELEASE_DATE -> songs.sortedBy { it.song.song.getDateLong() }
        }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)

        Pair(playlist, sortedSongs)
    }.stateIn(viewModelScope, SharingStarted.Lazily, Pair(null, emptyList()))

    // --- Phase 2: Analysis Logic ---

    private val _analysisProgress = MutableStateFlow<Float?>(null)
    val analysisProgress = _analysisProgress.asStateFlow()

    init {
        // Fix playlist song order
        viewModelScope.launch(Dispatchers.IO) {
            val sortedSongs = playlistWithSongs.first().second.sortedWith(compareBy({ it.map.position }, { it.map.id }))
            database.transaction {
                sortedSongs.forEachIndexed { index, song ->
                    if (song.map.position != index) {
                        update(song.map.copy(position = index))
                    }
                }
            }
        }
        // Start observing analysis progress immediately (in case user re-enters screen while working)
        monitorAnalysis()
    }

    fun analyzePlaylist() {
        Log.e("LocalPlaylistViewModel", "=== analyzePlaylist() FUNCTION ENTERED ===")

        viewModelScope.launch(Dispatchers.IO) {
            Log.e("LocalPlaylistViewModel", "Inside coroutine on IO dispatcher")

            val songs = playlistWithSongs.value.second
            Log.e("LocalPlaylistViewModel", "Total songs in playlist: ${songs.size}")

            // Get downloads that need analysis
            val songsNeedingAnalysis = songs.mapNotNull { playlistSong ->
                val songId = playlistSong.song.id
                val download = downloadDao.getDownload(songId)

                if (download != null && download.bpm == null) {
                    Log.d("LocalPlaylistViewModel", "Song $songId: has download, bpm=${download.bpm}, path=${download.localPath}")
                    Pair(playlistSong.song, download)
                } else {
                    Log.d("LocalPlaylistViewModel", "Song $songId: download=${download != null}, bpm=${download?.bpm}")
                    null
                }
            }

            Log.e("LocalPlaylistViewModel", "Songs needing analysis: ${songsNeedingAnalysis.size}")

            if (songsNeedingAnalysis.isEmpty()) {
                Log.w("LocalPlaylistViewModel", "No songs to analyze - all songs either not downloaded or already analyzed!")
                return@launch
            }

            // Create a unique tag for this batch operation
            val tag = "analysis_playlist_$playlistId"
            Log.e("LocalPlaylistViewModel", "Creating work requests with tag: $tag")

            val requests = songsNeedingAnalysis.map { (song, download) ->
                Log.d("LocalPlaylistViewModel", "Creating request for songId: ${song.id}, path: ${download.localPath}")
                OneTimeWorkRequestBuilder<AnalysisWorker>()
                    .setInputData(workDataOf(
                        "songId" to song.id,
                        "path" to download.localPath
                    ))
                    .addTag(tag)
                    .build()
            }

            Log.e("LocalPlaylistViewModel", "Enqueuing ${requests.size} work requests to WorkManager...")
            if (requests.isEmpty()) {
                Log.e("LocalPlaylistViewModel", "No work requests to enqueue")
                return@launch
            }

            // Chain all requests: first request starts immediately, each subsequent request
            // waits for the previous one to complete
            var continuation = workManager.beginWith(requests.first())
            requests.drop(1).forEach { request ->
                continuation = continuation.then(request)
            }
            continuation.enqueue()
            Log.e("LocalPlaylistViewModel", "=== WORK ENQUEUED SUCCESSFULLY ===")
        }
    }

    private fun monitorAnalysis() {
        viewModelScope.launch {
            val tag = "analysis_playlist_$playlistId"

            workManager.getWorkInfosByTagFlow(tag).collect { workInfos ->
                if (workInfos.isEmpty()) {
                    _analysisProgress.value = null
                    return@collect
                }

                // Filter out cancelled works to avoid skewing progress if retried
                val validInfos = workInfos.filter { it.state != WorkInfo.State.CANCELLED }
                val total = validInfos.size
                if (total == 0) {
                    _analysisProgress.value = null
                    return@collect
                }

                val completed = validInfos.count { it.state.isFinished }

                if (completed == total) {
                    // Show 100% for a moment before hiding
                    _analysisProgress.value = 1f
                    delay(2000)
                    // Only clear if we are still at 100% (avoid race conditions)
                    if (_analysisProgress.value == 1f) {
                        _analysisProgress.value = null
                        // Optional: Prune finished work to keep WorkManager clean
                        workManager.pruneWork()
                    }
                } else {
                    _analysisProgress.value = completed.toFloat() / total.toFloat()
                }
            }
        }
    }
}
