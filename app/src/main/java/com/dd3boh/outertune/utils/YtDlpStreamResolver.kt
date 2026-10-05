package com.dd3boh.outertune.utils

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat

/**
 * Last-resort YouTube audio stream resolver, used only when every InnerTube client
 * (see YTPlayerUtils.STREAM_FALLBACK_CLIENTS) has failed. Backed by a bundled yt-dlp,
 * whose signature/n-param solving and client rotation are more current than
 * NewPipeExtractor's and are updated far more frequently.
 *
 * Deliberately narrow: this only resolves a playable audio URL + the headers it requires.
 * It does NOT replace search/browse (still InnerTube/`innertube` module), and the fields
 * InnerTube's player response gives that yt-dlp has no equivalent for — loudness
 * normalization (`audioConfig.loudnessDb`) and watch-history registration
 * (`playbackTracking.videostatsPlaybackUrl`) — are simply absent for a yt-dlp-sourced
 * stream. That's an accepted gap for a fallback path, not a bug.
 */
object YtDlpStreamResolver {
    private const val TAG = "YtDlpStreamResolver"

    data class ResolvedStream(
        val url: String,
        /** Headers the URL was minted for — omitting them is exactly the class of bug that
         *  caused the 403s this resolver exists to route around. Must be sent verbatim. */
        val headers: Map<String, String>,
        val itag: Int?,
        val mimeType: String,
        val bitrateKbps: Int,
        val sampleRateHz: Int?,
        val contentLength: Long?,
    )

    /**
     * Blocking call (shells out to the bundled Python/yt-dlp) — always invoke from Dispatchers.IO.
     * Returns null for any failure (not yet initialized, network error, extractor failure, no
     * audio format found) — callers treat that identically to "no fallback available".
     */
    fun resolveAudioStream(videoId: String): ResolvedStream? {
        return try {
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("-f", "bestaudio")
                addOption("--no-playlist")
            }
            val info = YoutubeDL.getInstance().getInfo(request)

            // With a single-format selector (-f bestaudio), yt-dlp flattens the chosen format's
            // fields onto the top-level info dict; requestedFormats carries the richer per-format
            // fields (abr/acodec/asr) that VideoInfo itself doesn't expose.
            val chosen: VideoFormat? = info.requestedFormats?.firstOrNull()
            val url = chosen?.url ?: info.url
            if (url == null) {
                Log.w(TAG, "[$videoId] yt-dlp returned no stream url")
                return null
            }

            val ext = chosen?.ext ?: info.ext ?: "webm"
            val acodec = chosen?.acodec?.takeIf { it.isNotBlank() && it != "none" } ?: "unknown"
            val bitrate = (chosen?.abr ?: chosen?.tbr ?: 0).let { if (it > 0) it else 128 }

            ResolvedStream(
                url = url,
                headers = chosen?.httpHeaders ?: info.httpHeaders ?: emptyMap(),
                itag = chosen?.formatId?.toIntOrNull() ?: info.formatId?.toIntOrNull(),
                mimeType = "audio/$ext; codecs=\"$acodec\"",
                bitrateKbps = bitrate,
                sampleRateHz = chosen?.asr?.takeIf { it > 0 },
                contentLength = (chosen?.fileSize?.takeIf { it > 0 }
                    ?: chosen?.fileSizeApproximate?.takeIf { it > 0 }),
            ).also {
                Log.i(TAG, "[$videoId] yt-dlp resolved a stream (itag=${it.itag}, ${it.bitrateKbps}kbps)")
            }
        } catch (e: Exception) {
            // Covers: not yet initialized, process/extraction failure, no network, etc.
            Log.w(TAG, "[$videoId] yt-dlp resolution failed: ${e.message}")
            null
        }
    }
}
