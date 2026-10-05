package com.dd3boh.outertune.playback.downloadManager

import android.net.Uri
import android.util.Log
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream


data class StreamSource(val url: String, val headers: Map<String, String> = emptyMap())

sealed class DownloadEvent {
    data class Progress(val mediaId: String, val bytesRead: Long, val contentLength: Long) : DownloadEvent()
    data class Success(val mediaId: String, val file: Uri) : DownloadEvent()
    data class Failure(val mediaId: String, val error: Throwable) : DownloadEvent()
}

class DownloadManagerOt(
    private val local: DownloadDirectoryManagerOt,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    /** Mints a fresh stream URL (+ headers) for a song whose current one started 403ing
     *  mid-download. [attempt] starts at 1, so callers can vary the source per attempt.
     *  Returns null when no new URL could be resolved. */
    private val refreshUrl: (suspend (mediaId: String, attempt: Int) -> StreamSource?)? = null,
) {
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 100)
    val events = _events.asSharedFlow()

    // One download at a time: parallel back-to-back range requests get the client 403'd
    private val downloadSemaphore = kotlinx.coroutines.sync.Semaphore(1)

    fun enqueue(
        mediaId: String,
        url: String,
        displayName: String? = null,
        abort: Boolean = false,
        /** Extra headers [url] must be requested with (non-empty for a yt-dlp-sourced URL —
         *  see YtDlpStreamResolver / PlaybackData.streamHeaders). Omitting them for such a URL
         *  reproduces the exact 403s that fallback exists to route around. */
        headers: Map<String, String> = emptyMap(),
        /** true: fetch [url] in byte ranges (needed for googlevideo URLs). false: one plain GET,
         *  for a server that returns the finished file (e.g. the ytmusic-api backend). */
        chunked: Boolean = true,
    ) {
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
                if (!chunked) {
                    downloadWhole(mediaId, url, headers, displayName)
                    return@launch
                }

                // googlevideo 403s a single un-ranged GET of the whole file, but serves the same
                // URL fine in byte ranges — so fetch it in CHUNK_LENGTH ranges, like playback does.
                val refresh = refreshUrl?.let { resolve ->
                    { attempt: Int -> runBlocking { resolve(mediaId, attempt) } }
                }
                ChunkedHttpInputStream(httpClient, StreamSource(url, headers), refresh).use { input ->
                    Log.d("DownloadManagerOt", "Attempting to save stream to disk...")
                    val saved = local.saveFile(mediaId, input, displayName = displayName)

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

    /** Single GET of [url], streamed straight to disk. Throws on a non-2xx response. */
    private fun downloadWhole(mediaId: String, url: String, headers: Map<String, String>, displayName: String?) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()

        httpClient.newCall(request).execute().use { resp ->
            Log.d("DownloadManagerOt", "Response Code: ${resp.code}")
            if (!resp.isSuccessful) {
                // The backend explains failures as {"detail": "..."}; surface that.
                val detail = runCatching { resp.body.string() }.getOrNull()
                    ?.let { Regex("\"detail\"\\s*:\\s*\"(.*?)\"").find(it)?.groupValues?.get(1) ?: it.take(200) }
                throw IllegalStateException("HTTP ${resp.code}${detail?.let { ": $it" } ?: ""}")
            }
            Log.d("DownloadManagerOt", "Content Length: ${resp.body.contentLength()} bytes")

            val saved = local.saveFile(mediaId, resp.body.byteStream(), displayName = displayName)
                ?: throw IOException("Failed to create file or write stream")
            Log.i("DownloadManagerOt", "File saved successfully: $saved")
            _events.tryEmit(DownloadEvent.Success(mediaId, saved))
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

/**
 * Reads [url] as consecutive `Range: bytes=a-b` requests of [chunkLength] bytes each, exposing
 * them as one continuous stream. The first response's `Content-Range` gives the total size.
 */
private class ChunkedHttpInputStream(
    private val httpClient: OkHttpClient,
    private var source: StreamSource,
    /** Called on a 403 to get a replacement URL; the failed range is then retried from it. */
    private val refresh: ((attempt: Int) -> StreamSource?)?,
    private val chunkLength: Long = CHUNK_LENGTH,
) : InputStream() {
    private var refreshes = 0
    private var position = 0L
    private var totalLength: Long? = null
    private var response: Response? = null
    private var current: InputStream? = null

    private fun openNextChunk(): Boolean {
        closeCurrent()
        totalLength?.let { if (position >= it) return false }

        // Pace requests a little so a download looks like playback rather than a bulk scrape.
        if (position > 0) Thread.sleep(CHUNK_DELAY_MS)

        val end = position + chunkLength - 1
        var resp: Response
        while (true) {
            val request = Request.Builder().url(source.url).apply {
                source.headers.forEach { (name, value) -> header(name, value) }
                header("Range", "bytes=$position-$end")
            }.build()

            resp = httpClient.newCall(request).execute()
            Log.d("DownloadManagerOt", "Range $position-$end -> ${resp.code}")
            if (resp.code != 403 || refresh == null || refreshes >= MAX_REFRESHES) break

            // The URL got blocked (or expired) partway through: swap in a fresh one and retry
            // this same range, keeping everything already written.
            resp.close()
            refreshes++
            Log.w("DownloadManagerOt", "403 at byte $position; refreshing stream URL (attempt $refreshes/$MAX_REFRESHES)")
            Thread.sleep(REFRESH_BACKOFF_MS * refreshes)
            source = refresh(refreshes) ?: run {
                Log.w("DownloadManagerOt", "Could not resolve a fresh stream URL")
                return@run source
            }
        }
        if (resp.code == 416) { // past the end
            resp.close()
            return false
        }
        if (!resp.isSuccessful) {
            resp.close()
            throw IllegalStateException("HTTP Request Failed: ${resp.code} ${resp.message}")
        }
        if (resp.code == 200) {
            // Server ignored the Range header and sent the whole file — just stream that.
            totalLength = Long.MAX_VALUE
        } else if (totalLength == null) {
            totalLength = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            Log.d("DownloadManagerOt", "Content Length: $totalLength bytes")
        }
        response = resp
        current = resp.body.byteStream()
        return true
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val stream = current ?: if (openNextChunk()) current!! else return -1
            val n = stream.read(b, off, len)
            if (n != -1) {
                position += n
                return n
            }
            // This chunk is exhausted. A short chunk with an unknown total means end of file.
            if (totalLength == null || totalLength == Long.MAX_VALUE) {
                closeCurrent()
                return -1
            }
            closeCurrent()
        }
    }

    private fun closeCurrent() {
        current = null
        response?.close()
        response = null
    }

    override fun close() = closeCurrent()

    companion object {
        const val CHUNK_LENGTH = 512 * 1024L
        const val CHUNK_DELAY_MS = 200L
        const val MAX_REFRESHES = 3
        const val REFRESH_BACKOFF_MS = 2000L
    }
}

