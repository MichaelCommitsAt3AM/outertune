package com.dd3boh.outertune.viewmodels


import androidx.lifecycle.ViewModel
import androidx.work.WorkManager
import com.dd3boh.outertune.db.daos.DownloadDao
import com.dd3boh.outertune.db.entities.Download
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadDao: DownloadDao,
    private val downloadManager: DownloadDirectoryManagerOt,
    private val workManager: WorkManager
) : ViewModel() {

    // Download a song
    suspend fun downloadSong(song: Song, inputStream: InputStream): Result<String> {
        return try {
            // Save file and get absolute path
            val uri = downloadManager.saveFile(song.id, inputStream, song.title)
            val path = uri?.path ?: throw _root_ide_package_.kotlinx.io.IOException("Failed to save file")

            // Create download record
            val download = Download(
                songId = song.id,
                localPath = path,
                downloadedAt = System.currentTimeMillis(),
                fileSize = null
            )
            downloadDao.insertDownload(download)

            Result.success(path)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Check if downloaded
    fun isDownloaded(songId: String): Flow<Boolean> = flow {
        emit(downloadDao.getDownload(songId) != null)
    }

    // Get download info
    fun getDownload(songId: String): Flow<Download?> = flow {
        emit(downloadDao.getDownload(songId))
    }
}
