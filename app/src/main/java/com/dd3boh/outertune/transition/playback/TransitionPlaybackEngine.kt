package com.dd3boh.outertune.transition.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import com.dd3boh.outertune.utils.TransitionMixer
import com.dd3boh.outertune.utils.analysis.AudioDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.floor

/**
 * Real-time audio engine for executing DJ transitions.
 * USES: Windowed PCM streaming and a custom software mixer for sample-accurate sync.
 */
class TransitionPlaybackEngine(
    private val context: Context
) {
    // --- Public State ---
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _decksReady = MutableStateFlow(false)
    val decksReady: StateFlow<Boolean> = _decksReady.asStateFlow()

    private val _loadingError = MutableStateFlow<String?>(null)
    val loadingError: StateFlow<String?> = _loadingError.asStateFlow()

    // --- Audio Sources (Streaming) ---
    private var sourceA: PcmSource? = null
    private var sourceB: PcmSource? = null
    private var sampleRateA: Int = 44100
    private var sampleRateB: Int = 44100

    // --- Playback State ---
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var playbackJob: Job? = null
    private var loadingJob: Job? = null
    private var activeConfig = TransitionConfig()
    
    // Track last loaded paths to avoid redundant loading
    private var lastLoadedPathA: String? = null
    private var lastLoadedPathB: String? = null
    
    // Playback Cursors (in Frames)
    private var cursorA: Double = 0.0
    private var cursorB: Double = 0.0

    // --- AudioTrack ---
    private val SAMPLE_RATE = 48000
    private val BUFFER_SIZE_BYTES = 8192
    
    private var audioTrack: AudioTrack? = null
    
    init {
        // Initialize AudioTrack
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BUFFER_SIZE_BYTES)
        
        audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    companion object {
        private const val TAG = "TransitionPlaybackEngine"
    }

    /**
     * Initializes streaming PCM sources.
     * Decks are ready as soon as sources are prepared (no full-track decoding).
     */
    fun prewarmDecks(pathA: String, pathB: String) {
        // Check if we already have these tracks loaded
        if (pathA == lastLoadedPathA && pathB == lastLoadedPathB && _decksReady.value) {
            Log.d(TAG, "Decks already loaded for these paths, skipping")
            return
        }
        
        // Cancel any existing loading job
        loadingJob?.cancel()
        
        loadingJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting prewarmDecks (streaming) for A=$pathA, B=$pathB")
            _decksReady.value = false
            _loadingError.value = null
            
            try {
                // Release old sources if any
                sourceA?.release()
                sourceB?.release()
                
                // Create and prepare new sources
                val srcA = StreamingPcmSource(context, pathA, scope)
                srcA.prepare()
                sourceA = srcA
                sampleRateA = srcA.sampleRate
                
                val srcB = StreamingPcmSource(context, pathB, scope)
                srcB.prepare()
                sourceB = srcB
                sampleRateB = srcB.sampleRate
                
                Log.d(TAG, "Initialized Sources: A=${srcA.totalFrames} frames @ ${sampleRateA}Hz, B=${srcB.totalFrames} frames @ ${sampleRateB}Hz")
                Log.d(TAG, "Setting _decksReady = true (immediate readiness)")
                _decksReady.value = true
                lastLoadedPathA = pathA
                lastLoadedPathB = pathB
                Log.d(TAG, "Decks ready, value is now: ${_decksReady.value}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare PCM sources", e)
                _loadingError.value = "Failed to initialize audio sources: ${e.message}"
                _decksReady.value = false
            }
        }
    }

    fun updateConfig(config: TransitionConfig) {
        this.activeConfig = config
    }

    fun play(plan: TransitionPlan, config: TransitionConfig, powerSaveMode: Boolean = false) {
        if (!_decksReady.value) {
            Log.e(TAG, "Play requested but decks not ready")
            return
        }
        
        activeConfig = config
        stop() // Ensure no double-playing

        // --- 1. Calculate Start Positions ---
        // PREROLL: Start 3 seconds before the anchor beat
        val PREROLL_SECONDS = 3.0
        
        val timeAnchorA = TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA)
        val timeAnchorB = TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB)

        // Calculate start time for A (seconds)
        val seekTimeA = (timeAnchorA - PREROLL_SECONDS).coerceAtLeast(0.0)
        
        // Calculate start time for B (seconds)
        val seekTimeB = (timeAnchorB - (PREROLL_SECONDS * plan.initialSpeedB)).coerceAtLeast(0.0)

        // Set Cursors (Frames)
        cursorA = seekTimeA * sampleRateA
        cursorB = seekTimeB * sampleRateB
        
        audioTrack?.play()
        _playbackState.value = PlaybackState(isPlaying = true)

        // Launch Mixer Loop (seeks sources internally before reading)
        playbackJob = scope.launch(Dispatchers.Default) {
            // Seek sources BEFORE starting to read PCM
            try {
                withContext(Dispatchers.IO) {
                    sourceA?.seek(cursorA.toLong())
                    sourceB?.seek(cursorB.toLong())
                    Log.d(TAG, "✅ Seeked sources to: A=${cursorA.toLong()}, B=${cursorB.toLong()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Seek failed", e)
                withContext(Dispatchers.Main) { stop() }
                return@launch
            }
            
            Log.d(TAG, "🎵 Starting mixer loop...")
            runMixerLoop(plan, powerSaveMode)
            Log.d(TAG, "🛑 Mixer loop ended")
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.pause()
        audioTrack?.flush()
        _playbackState.value = PlaybackState(isPlaying = false)
    }

    fun release() {
        stop()
        loadingJob?.cancel()
        audioTrack?.release()
        audioTrack = null
        sourceA?.release()
        sourceB?.release()
        sourceA = null
        sourceB = null
    }

    /**
     * The High-Priority Audio Mixer Loop.
     * Fills AudioTrack buffer with mixed samples using streaming PCM sources.
     */
    private suspend fun runMixerLoop(plan: TransitionPlan, powerSaveMode: Boolean) {
        val srcA = sourceA ?: return
        val srcB = sourceB ?: return
        val track = audioTrack ?: return

        val outBuffer = ShortArray(BUFFER_SIZE_BYTES / 2) // Stereo Samples
        
        // Pre-calculate rate conversion constants
        val rateA_base = sampleRateA.toDouble() / SAMPLE_RATE.toDouble()
        val rateB_base = sampleRateB.toDouble() / SAMPLE_RATE.toDouble()
        
        // Transition Logic Variables
        val transitionStartBeatA = plan.anchorBeatA
        var currentSpeedB = plan.initialSpeedB
        val initialSpeedB = plan.initialSpeedB
        
        // Post-Roll Logic
        var isPostRoll = false
        var postRollStartTime = 0L
        val RAMP_DURATION_MS = 20_000L
        
        // UI Throttling
        var lastUiUpdate = 0L
        val UI_UPDATE_INTERVAL_MS = if (powerSaveMode) 100L else 32L // 10fps vs 30fps
        
        // Debug tracking
        var loopCount = 0
        var lastDebugLog = 0L
        val DEBUG_LOG_INTERVAL_MS = 1000L

        while (currentCoroutineContext().isActive) {
            loopCount++
            val config = activeConfig
            
            // 1. Determine Current Progress (Beat Domain)
            val currentTimeA = cursorA / sampleRateA
            val currentBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, currentTimeA)
            
            // Calculate Mixing Progress
            // If duration is 0, we immediately jump to 1.0
            val progress = if (plan.transitionDurationBeats > 0)
                ((currentBeatA - transitionStartBeatA) / plan.transitionDurationBeats).toFloat()
            else 1.0f
            
            // --- STATE MACHINE ---
            if (progress >= 1.0f) {
                if (!isPostRoll) {
                    isPostRoll = true
                    postRollStartTime = System.currentTimeMillis()
                }
            }
            
            // Speed Ramping (Post-Roll)
            if (isPostRoll) {
                val elapsedPostRoll = System.currentTimeMillis() - postRollStartTime
                val rampProgress = (elapsedPostRoll.toDouble() / RAMP_DURATION_MS).coerceIn(0.0, 1.0)
                
                // Interpolate from InitialSpeed -> 1.0 (Original Speed)
                currentSpeedB = initialSpeedB + (1.0 - initialSpeedB) * rampProgress
                
                // Stop condition: After ramp is fully done (plus maybe a buffer?)
                if (rampProgress >= 1.0 && elapsedPostRoll > RAMP_DURATION_MS + 1000) {
                     // Auto-stop engine after test period
                     withContext(Dispatchers.Main) { stop() }
                     break
                }
            }

            // Update UI State (throttled)
            val now = System.currentTimeMillis()
            if (now - lastUiUpdate > UI_UPDATE_INTERVAL_MS) {
                _playbackState.value = PlaybackState(
                    true,
                    currentBeatA.toFloat(),
                    TransitionMath.getBeatForTimestamp(plan.gridB, cursorB / sampleRateB).toFloat(),
                    0f 
                )
                lastUiUpdate = now
            }
            
            // 2. Calculate Volumes
            val mixStateA = TransitionMixer.getMixState("A", progress, config.overlapMode, config.eqMode, config.effectMode)
            val mixStateB = TransitionMixer.getMixState("B", progress, config.overlapMode, config.eqMode, config.effectMode)
            
            var gainA = mixStateA.volume
            var gainB = mixStateB.volume
            
            // Post-Roll Override: A is silent, B is full
            if (isPostRoll) {
                gainA = 0f
                gainB = 1f
            } else if (progress < 0f) {
                // Pre-Roll: A is full, B is silent (but engine runs to keep sync)
                gainA = 1f
                gainB = 0f
            }

            val stepA = rateA_base 
            val stepB = rateB_base * currentSpeedB

            // 3. Fill Buffer with Streaming PCM
            val framesToFill = outBuffer.size / 2
            
            for (i in 0 until framesToFill) {
                // Read Track A with streaming
                var sampleLA = 0f
                var sampleRA = 0f
                
                if (gainA > 0.001f) {
                    val samples = readSampleFromSource(srcA, cursorA)
                    sampleLA = samples.first
                    sampleRA = samples.second
                }
                
                // Read Track B with streaming
                var sampleLB = 0f
                var sampleRB = 0f
                
                if (gainB > 0.001f) {
                    val samples = readSampleFromSource(srcB, cursorB)
                    sampleLB = samples.first
                    sampleRB = samples.second
                }

                // Mix
                val mixedL = (sampleLA * gainA) + (sampleLB * gainB)
                val mixedR = (sampleRA * gainA) + (sampleRB * gainB)
                
                // Hard Limiter
                outBuffer[i * 2] = mixedL.coerceIn(-32767f, 32767f).toInt().toShort()
                outBuffer[i * 2 + 1] = mixedR.coerceIn(-32767f, 32767f).toInt().toShort()
                
                // Advance Cursors
                cursorA += stepA
                cursorB += stepB
            }

            // 4. Write to AudioTrack
            track.write(outBuffer, 0, outBuffer.size)
            
            // Debug logging every second
            val now2 = System.currentTimeMillis()
            if (now2 - lastDebugLog > DEBUG_LOG_INTERVAL_MS) {
                Log.d(TAG, "📊 Mixer: loop=$loopCount, cursorA=${cursorA.toLong()}, cursorB=${cursorB.toLong()}, gainA=$gainA, gainB=$gainB, progress=$progress")
                lastDebugLog = now2
            }
        }
    }
    
    /**
     * Read and interpolate a stereo sample from the ring buffer.
     * The ring buffer is continuously filled by background decode jobs in StreamingPcmSource.
     * 
     * Note: No context switching needed - ring buffer reads are thread-safe and non-blocking.
     */
    private suspend fun readSampleFromSource(
        source: PcmSource,
        cursor: Double
    ): Pair<Float, Float> {
        val frac = (cursor - floor(cursor)).toFloat()
        
        // Read 2 frames for linear interpolation
        // Ring buffer read is already thread-safe, no context switch needed
        val samples = source.readFrames(2)
        
        if (samples.size < 4) {
            // Buffer underrun - return silence
            // The background decoder will catch up
            return Pair(0f, 0f)
        }
        
        // Linear interpolation between frame N and N+1
        val sampleL = samples[0] + frac * (samples[2] - samples[0])
        val sampleR = samples[1] + frac * (samples[3] - samples[1])
        
        return Pair(sampleL, sampleR)
    }
}

/**
 * Represents the current playback state of the transition engine.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentBeatA: Float = 0f,
    val currentBeatB: Float = 0f,
    val progress: Float = 0f
)