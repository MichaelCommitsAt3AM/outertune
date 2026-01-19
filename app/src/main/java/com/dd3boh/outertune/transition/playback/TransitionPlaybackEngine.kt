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
 * USES: RAM-based buffers and a custom software mixer for sample-accurate sync.
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

    // --- Audio Data (RAM) ---
    // Interleaved Stereo ShortArrays
    private var bufferA: ShortArray? = null
    private var bufferB: ShortArray? = null
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
     * Loads songs into RAM asynchronously.
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
            Log.d(TAG, "Starting prewarmDecks for A=$pathA, B=$pathB")
            _decksReady.value = false
            _loadingError.value = null
            
            val resultA = AudioDecoder.decodeToStereo(context, pathA)
            if (resultA == null) {
                 _loadingError.value = "Failed to load Track A"
                 return@launch
            }

            val resultB = AudioDecoder.decodeToStereo(context, pathB)
            if (resultB == null) {
                 _loadingError.value = "Failed to load Track B"
                 return@launch
            }

            bufferA = resultA.first
            sampleRateA = resultA.second
            
            bufferB = resultB.first
            sampleRateB = resultB.second
            
            Log.d(TAG, "Loaded Decks: A=${bufferA!!.size/2} frames @ ${sampleRateA}Hz, B=${bufferB!!.size/2} frames @ ${sampleRateB}Hz")
            Log.d(TAG, "Setting _decksReady = true")
            _decksReady.value = true
            lastLoadedPathA = pathA
            lastLoadedPathB = pathB
            Log.d(TAG, "After setting _decksReady, value is now: ${_decksReady.value}")
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

        // Launch Mixer Loop
        playbackJob = scope.launch(Dispatchers.Default) {
            runMixerLoop(plan, powerSaveMode)
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
        audioTrack?.release()
        audioTrack = null
        bufferA = null
        bufferB = null
    }

    /**
     * The High-Priority Audio Mixer Loop.
     * Fills AudioTrack buffer with mixed samples.
     */
    private suspend fun runMixerLoop(plan: TransitionPlan, powerSaveMode: Boolean) {
        val bA = bufferA ?: return
        val bB = bufferB ?: return
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

        while (currentCoroutineContext().isActive) {
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

            // 3. Fill Buffer
            val framesToFill = outBuffer.size / 2
            
            for (i in 0 until framesToFill) {
                // Read Track A
                var sampleLA = 0f
                var sampleRA = 0f
                
                // Optimization: Don't read if gain is 0 (Power Save)
                if (gainA > 0.001f && cursorA + 1 < bA.size / 2) {
                    val idx = floor(cursorA).toInt()
                    val frac = (cursorA - idx).toFloat()
                    val offset = idx * 2
                    // Boundary check (lazy)
                    if (offset + 3 < bA.size) {
                        sampleLA = bA[offset] + frac * (bA[offset+2] - bA[offset])
                        sampleRA = bA[offset+1] + frac * (bA[offset+3] - bA[offset+1])
                    }
                }
                
                // Read Track B
                var sampleLB = 0f
                var sampleRB = 0f
                
                if (gainB > 0.001f && cursorB + 1 < bB.size / 2) {
                    val idx = floor(cursorB).toInt()
                    val frac = (cursorB - idx).toFloat()
                    val offset = idx * 2
                    if (offset + 3 < bB.size) {
                        sampleLB = bB[offset] + frac * (bB[offset+2] - bB[offset])
                        sampleRB = bB[offset+1] + frac * (bB[offset+3] - bB[offset+1])
                    }
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
        }
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