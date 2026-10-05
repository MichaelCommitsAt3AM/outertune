package com.dd3boh.outertune.transition.editor

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.transition.engine.DeckFactory
import com.dd3boh.outertune.transition.engine.DeckPair
import com.dd3boh.outertune.transition.engine.ProcessorDeckEffects
import com.dd3boh.outertune.transition.engine.MixTuning
import com.dd3boh.outertune.transition.engine.TransitionRenderer
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays a transition preview in the editor with the same [TransitionRenderer] that playlist
 * playback uses: Track A starts a moment before the preroll, and from the preroll on the renderer
 * brings Track B in exactly as it would in a playlist.
 *
 * The decks stay paused and prepared between previews. Must be used on the main thread.
 */
class PreviewSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    data class State(
        val isPlaying: Boolean = false,
        /** Current beat on Track A, for the editor's playhead. */
        val beatA: Double? = null,
        val phaseErrorBeats: Double = 0.0,
    )

    private val factory = DeckFactory(context, { DefaultDataSource.Factory(context) })
    private val renderer = TransitionRenderer(ProcessorDeckEffects(factory))
    private var decks: DeckPair? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var playJob: Job? = null

    /** Loads both songs (local files or content URIs), paused. */
    fun load(pathA: String, pathB: String) {
        stop()
        loadJob?.cancel()
        _ready.value = false
        _error.value = null

        val pair = decks ?: DeckPair(factory).also { decks = it }
        pair.active.apply {
            stop()
            setMediaItem(mediaItemFor(pathA))
            volume = 0f
            playWhenReady = false
            prepare()
        }
        pair.prepareStandby(mediaItemFor(pathB), 0L)

        loadJob = scope.launch {
            val ok = pair.awaitReady(pair.active) && pair.awaitReady(pair.standby)
            _ready.value = ok
            if (!ok) _error.value = context.getString(R.string.mix_editor_error_preview)
        }
    }

    /**
     * @param config read every tick, so edits apply while the preview plays
     * @param gainA, gainB loudness-normalization gains, as in playlist playback
     */
    fun play(plan: TransitionPlan, config: () -> TransitionConfig, gainA: Float, gainB: Float) {
        val pair = decks ?: return
        if (!_ready.value) return
        playJob?.cancel()
        _state.value = State(isPlaying = true)

        playJob = scope.launch {
            val a = pair.active
            val b = pair.standby
            val anchorMs = (TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA) * 1000).toLong()
            val prerollStartMs = anchorMs - (MixTuning.PREROLL_SECONDS * 1000).toLong()

            try {
                a.volume = gainA
                a.setPlaybackSpeed(1f)
                a.seekTo((prerollStartMs - (MixTuning.PREVIEW_LEAD_IN_SECONDS * 1000).toLong()).coerceAtLeast(0))
                a.play()

                // Lead-in: A plays alone until the point where a playlist would start the renderer.
                while (a.currentPosition < prerollStartMs) {
                    _state.value = State(
                        isPlaying = true,
                        beatA = TransitionMath.getBeatForTimestamp(plan.gridA, a.currentPosition / 1000.0)
                    )
                    delay(MixTuning.TICK_MS)
                }

                pair.linked = true
                renderer.run(a, b, plan, config, gainA, gainB, "preview") { frame ->
                    _state.value = State(isPlaying = true, beatA = frame.beatA, phaseErrorBeats = frame.phaseErrorBeats)
                }
            } finally {
                pair.linked = false
                a.pause()
                b.pause()
                _state.value = State()
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        _state.value = State()
    }

    fun release() {
        stop()
        loadJob?.cancel()
        decks?.release()
        decks = null
    }

    private fun mediaItemFor(path: String): MediaItem =
        MediaItem.fromUri(if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path)))
}
