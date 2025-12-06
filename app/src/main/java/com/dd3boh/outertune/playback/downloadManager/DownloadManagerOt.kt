package com.dd3boh.outertune.playback.downloadManager

import android.net.Uri
import android.util.Log
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream


sealed class DownloadEvent {
    data class Progress(val mediaId: String, val bytesRead: Long, val contentLength: Long) : DownloadEvent()
    data class Success(val mediaId: String, val file: Uri) : DownloadEvent()
    data class Failure(val mediaId: String, val error: Throwable) : DownloadEvent()
}

class DownloadManagerOt(
    private val local: DownloadDirectoryManagerOt,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 100)
    val events = _events.asSharedFlow()

    // Limit to 3 concurrent downloads
    private val downloadSemaphore = kotlinx.coroutines.sync.Semaphore(3)

    fun enqueue(mediaId: String, url: String, displayName: String? = null, abort: Boolean = false) {
        Log.d("DownloadManagerOt", "Enqueue called: $displayName [$mediaId]")

        // if already exists, immediately emit success
        local.getFilePathIfExists(mediaId)?.let {
            Log.i("DownloadManagerOt", "File already exists at $it. Skipping download.")
            _events.tryEmit(DownloadEvent.Success(mediaId, it))
            return
        }

        if (abort) {
            _events.tryEmit(DownloadEvent.Failure(mediaId, Exception("Could not resolve download: $displayName")))
            return
        }

        scope.launch {
            // WAIT FOR AVAILABLE SLOT
            downloadSemaphore.acquire()
            try {
                Log.d("DownloadManagerOt", "Starting HTTP request for $mediaId")
                val request = Request.Builder().url(url).build()

                httpClient.newCall(request).execute().use { resp ->
                    Log.d("DownloadManagerOt", "Response Code: ${resp.code}")
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP Request Failed: ${resp.code} ${resp.message}")
                    }

                    val body = resp.body
                    Log.d("DownloadManagerOt", "Content Length: ${body.contentLength()} bytes")

                    // Save directly without progress tracking
                    Log.d("DownloadManagerOt", "Attempting to save stream to disk...")
                    val saved = local.saveFile(mediaId, body.byteStream(), displayName = displayName)

                    if (saved != null) {
                        Log.i("DownloadManagerOt", "File saved successfully: $saved")
                        _events.tryEmit(DownloadEvent.Success(mediaId, saved))
                    } else {
                        Log.e("DownloadManagerOt", "Failed to save file: saveFile returned null")
                        throw IOException("Failed to create file or write stream")
                    }
                }
            } catch (e: Throwable) {
                Log.e("DownloadManagerOt", "Download Exception for $mediaId", e)
                reportException(e)
                _events.tryEmit(DownloadEvent.Failure(mediaId, e))
            } finally {
                // RELEASE SLOT
                downloadSemaphore.release()
            }
        }
    }

    fun enqueue(mediaId: String, data: ByteArray, displayName: String? = null) {
        // if already exists, immediately emit success
        local.getFilePathIfExists(mediaId)?.let {
            _events.tryEmit(DownloadEvent.Success(mediaId, it))
            return
        }

        scope.launch {
            downloadSemaphore.acquire()
            try {
                // Save directly without progress tracking
                val saved = local.saveFile(mediaId, data.inputStream(), displayName = displayName)
                if (saved != null) {
                    _events.tryEmit(DownloadEvent.Success(mediaId, saved))
                } else {
                    throw IOException("Failed to save file")
                }
            } catch (e: Throwable) {
                reportException(e)
                _events.tryEmit(DownloadEvent.Failure(mediaId, e))
            } finally {
                downloadSemaphore.release()
            }
        }
    }

    fun enqueueAll(pairs: List<Pair<String, String>>) {
        pairs.forEach { (id, url) -> enqueue(id, url) }
    }

    fun getFilePath(mediaId: String): Uri? = local.getFilePathIfExists(mediaId)
}
