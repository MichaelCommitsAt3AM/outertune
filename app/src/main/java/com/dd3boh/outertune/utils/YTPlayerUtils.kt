/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.YTPlayerUtils.MAIN_CLIENT
import com.dd3boh.outertune.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.dd3boh.outertune.utils.YTPlayerUtils.validateStatus
import com.dd3boh.outertune.utils.potoken.PoTokenGenerator
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

    /** Conservative default cache lifetime for yt-dlp-sourced URLs (no expiry field is exposed
     *  the way InnerTube's streamingData.expiresInSeconds is) — short enough that a stale cached
     *  entry just gets re-resolved rather than served past expiry. */
    private const val YTDLP_STREAM_EXPIRES_IN_SECONDS = 3600

    /** Safety margin so a cached URL is re-resolved before googlevideo actually rejects it. */
    private const val STREAM_EXPIRY_MARGIN_SECONDS = 60

    /**
     * Seconds until a googlevideo URL expires, from its `expire=` (epoch seconds) query
     * parameter, minus a safety margin. Null when the URL has no usable expiry.
     */
    private fun expiresInSecondsFromUrl(url: String): Int? {
        val expireEpoch = runCatching { android.net.Uri.parse(url).getQueryParameter("expire") }
            .getOrNull()?.toLongOrNull() ?: return null
        val remaining = expireEpoch - System.currentTimeMillis() / 1000 - STREAM_EXPIRY_MARGIN_SECONDS
        return remaining.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * [com.zionhuang.innertube.models.YouTubeClient.ANDROID_VR_NO_AUTH] Is temporally used as it is out only working client
     * [com.zionhuang.innertube.models.YouTubeClient.WEB_REMIX] should be preferred here because currently it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     */
    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_NO_AUTH

    /** The client whose stream last validated; tried first next time. */
    @Volatile
    private var lastWorkingClient: YouTubeClient? = null

    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        // WEB_REMIX carries a po_token (see PoTokenGenerator, already generated unconditionally
        // above) and is the client most likely to pass YouTube's bot check. Re-enabled after
        // bumping NewPipeExtractor past the "could not parse deobfuscation function" issue that
        // used to block it; if that error reappears in logs, NewPipe still can't handle its cipher.
        WEB_REMIX,
        ANDROID,
        IOS, // known to sometimes 403 real fetches ~30s in; kept as a fallback, not first choice
        TVHTML5_SIMPLY_EMBEDDED_PLAYER, // requires login; skipped automatically when signed out
    )


    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        /** Extra HTTP headers [streamUrl] must be requested with. Empty for InnerTube-sourced
         *  streams; non-empty when [streamUrl] came from the yt-dlp fallback (see
         *  [YtDlpStreamResolver]) — omitting them is exactly the class of bug that made this
         *  fallback necessary in the first place, so callers MUST send them verbatim. */
        val streamHeaders: Map<String, String> = emptyMap(),
    )

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Log.d(TAG, "Playback info requested: $videoId")

        /**
         * This is required for some clients to get working streams however
         * it should not be forced for the [MAIN_CLIENT] because the response of the [MAIN_CLIENT]
         * is required even if the streams won't work from this client.
         * This is why it is allowed to be null.
         */
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)

        val isLoggedIn = YouTube.cookie != null
        val sessionId =
            if (isLoggedIn) {
                // signed in sessions use dataSyncId as identifier
                YouTube.dataSyncId
            } else {
                // signed out sessions use visitorData as identifier
                YouTube.visitorData
            }

        Log.d(TAG, "[$videoId] signatureTimestamp: $signatureTimestamp, isLoggedIn: $isLoggedIn")

        val (webPlayerPot, webStreamingPot) = getWebClientPoTokenOrNull(videoId, sessionId)?.let {
            Pair(it.playerRequestPoToken, it.streamingDataPoToken)
        } ?: Pair(null, null).also {
            Log.w(TAG, "[$videoId] No po token")
        }

        val mainPlayerResponse =
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, webPlayerPot)
                .getOrThrow()

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null

        var streamPlayerResponse: PlayerResponse? = null
        // The main client first (its response is already here), then the fallbacks; but if a
        // fallback produced the last working stream, try it before anything else. Each try costs
        // a validation round trip, so this saves several on every song once one client works.
        val preferred = lastWorkingClient
        val candidates = (listOf(MAIN_CLIENT) + STREAM_FALLBACK_CLIENTS)
            .sortedByDescending { it === preferred }
        for (client in candidates) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // load the client's player response
            if (client === MAIN_CLIENT) {
                Log.d(TAG, "Trying client: ${MAIN_CLIENT.clientName}")
                streamPlayerResponse = mainPlayerResponse
            } else {
                Log.d(TAG, "Trying fallback client: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn) {
                    // skip client if it requires login but user is not logged in
                    continue
                }

                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot)
                        .getOrNull()
            }

            Log.d(TAG, "[$videoId] stream client: ${client.clientName}, " +
                    "playabilityStatus: ${streamPlayerResponse?.playabilityStatus?.let {
                        it.status + (it.reason?.let { " - $it" } ?: "")
                    }}")

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                format =
                    findFormat(
                        streamPlayerResponse,
                        audioQuality,
                        connectivityManager,
                    ) ?: continue
                streamUrl = findUrlOrNull(format, videoId) ?: continue
                streamExpiresInSeconds =
                    streamPlayerResponse.streamingData?.expiresInSeconds ?: continue

                if (client.useWebPoTokens && webStreamingPot != null) {
                    streamUrl += "&pot=$webStreamingPot";
                }

                // Always validate, including the last candidate: an unvalidated URL here is
                // exactly what silently reaches DownloadUtil/MusicService and 403s later. If
                // nothing validates we fall out of the loop with streamUrl == null and throw
                // a clear error below instead of handing back a URL that's likely dead.
                if (validateStatus(streamUrl)) {
                    // working stream found
                    Log.i(TAG, "[$videoId] [${client.clientName}] found working stream")
                    lastWorkingClient = client
                    break
                } else {
                    Log.w(TAG, "[$videoId] [${client.clientName}] got bad http status code")
                    if (client === lastWorkingClient) lastWorkingClient = null
                    format = null
                    streamUrl = null
                    streamExpiresInSeconds = null
                }
            }
        }

        // Every InnerTube client failed (or none validated). Last resort: yt-dlp, whose
        // signature/n-param solving is more current than NewPipeExtractor's. Only reached here —
        // never the default path — because it's meaningfully slower (spins up a bundled Python).
        if (streamUrl == null) {
            Log.w(TAG, "[$videoId] No InnerTube client produced a working stream; trying yt-dlp")
            val ytdlp = withContext(Dispatchers.IO) { YtDlpStreamResolver.resolveAudioStream(videoId) }
            if (ytdlp != null) {
                return@runCatching PlaybackData(
                    audioConfig,
                    videoDetails,
                    playbackTracking,
                    PlayerResponse.StreamingData.Format(
                        itag = ytdlp.itag ?: -1,
                        url = ytdlp.url,
                        mimeType = ytdlp.mimeType,
                        bitrate = ytdlp.bitrateKbps * 1000,
                        width = null,
                        height = null,
                        contentLength = ytdlp.contentLength,
                        quality = "AUDIO_QUALITY_UNKNOWN",
                        fps = null,
                        qualityLabel = null,
                        averageBitrate = null,
                        audioQuality = null,
                        approxDurationMs = null,
                        audioSampleRate = ytdlp.sampleRateHz,
                        audioChannels = null,
                        loudnessDb = null, // no InnerTube-equivalent from yt-dlp; normalization no-ops
                        lastModified = null,
                        signatureCipher = null, // url is already resolved, no cipher to decode
                    ),
                    ytdlp.url,
                    expiresInSecondsFromUrl(ytdlp.url) ?: YTDLP_STREAM_EXPIRES_IN_SECONDS,
                    ytdlp.headers,
                )
            }
        }

        if (streamPlayerResponse == null) {
            throw Exception("Bad stream player response")
        }
        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            throw PlaybackException(
                streamPlayerResponse.playabilityStatus.reason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }
        if (streamExpiresInSeconds == null) {
            throw Exception("Missing stream expire time")
        }
        if (format == null) {
            throw Exception("Could not find format")
        }
        if (streamUrl == null) {
            throw Exception("Could not find stream url")
        }

        Log.d(TAG, "[$videoId] stream url: $streamUrl")

        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? =
        playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     *
     * Uses a tiny ranged GET rather than HEAD: googlevideo's abuse checks appear to key off the
     * actual media-fetch path, so a HEAD can pass while the real GET a download performs 403s.
     */
    private fun validateStatus(url: String): Boolean {
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .header("Range", "bytes=0-1")
                .url(url)
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                return response.isSuccessful
            }
        } catch (e: Exception) {
            reportException(e)
        }
        return false
    }

    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure {
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onFailure {
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Wrapper around the [PoTokenGenerator.getWebClientPoToken] function which reports exceptions
     */
    private fun getWebClientPoTokenOrNull(videoId: String, sessionId: String?): PoTokenResult? {
        if (sessionId == null) {
            Log.d(TAG, "[$videoId] Session identifier is null")
            return null
        }
        try {
            return poTokenGenerator.getWebClientPoToken(videoId, sessionId)
        } catch (e: Exception) {
            reportException(e)
        }
        return null
    }
}