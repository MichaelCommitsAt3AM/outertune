package com.dd3boh.outertune.transition.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Production-grade streaming PCM source with ring buffer and background decoding.
 * 
 * Architecture:
 * - Background coroutine continuously decodes audio into a ring buffer (2-3s capacity)
 * - Real-time playback thread reads from ring buffer without I/O operations
 * - Seeking cancels decode job, clears buffer, and restarts from new position
 * 
 * This eliminates choppy audio caused by seeking and MediaCodec flushes during playback.
 */
class StreamingPcmSource(
    private val context: Context,
    private val filePath: String,
    private val scope: CoroutineScope
) : PcmSource {
    
    companion object {
        private const val TAG = "StreamingPcmSource"
        private const val DECODE_TIMEOUT_US = 10000L // 10ms
        private const val BUFFER_BACKOFF_MS = 10L // Delay when buffer is full
        private const val INITIAL_BUFFER_WAIT_MS = 200L // Wait for initial buffer fill
    }
    
    // MediaExtractor and MediaCodec instances
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    
    // Audio format properties
    override var sampleRate: Int = 0
        private set
    override var totalFrames: Long = 0
        private set
    private var channelCount: Int = 0
    private var durationUs: Long = 0
    
    // Ring buffer for decoded PCM
    private var ringBuffer: CircularPcmBuffer? = null
    
    // Background decode job
    private var decodeJob: Job? = null
    
    // Thread safety
    private val mutex = Mutex()
    
    // State tracking
    private var isPrepared = false
    private var isEOS = false // End of stream reached
    private var currentDecodeFrame: Long = 0
    
    override suspend fun prepare() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (isPrepared) return@withLock
                
                Log.d(TAG, "Preparing PCM source for: $filePath")
                
                try {
                    // Initialize MediaExtractor
                    val ext = MediaExtractor()
                    extractor = ext
                    
                    if (filePath.startsWith("content://")) {
                        ext.setDataSource(context, Uri.parse(filePath), null)
                    } else {
                        val file = File(filePath)
                        if (!file.exists()) {
                            throw IllegalArgumentException("File does not exist: $filePath")
                        }
                        ext.setDataSource(filePath)
                    }
                    
                    // Find audio track
                    var audioTrackIndex = -1
                    var audioFormat: MediaFormat? = null
                    for (i in 0 until ext.trackCount) {
                        val format = ext.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("audio/")) {
                            audioTrackIndex = i
                            audioFormat = format
                            break
                        }
                    }
                    
                    if (audioTrackIndex < 0 || audioFormat == null) {
                        throw IllegalStateException("No audio track found in: $filePath")
                    }
                    
                    ext.selectTrack(audioTrackIndex)
                    
                    // Extract format info
                    sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    durationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)
                    totalFrames = (durationUs * sampleRate) / 1_000_000L
                    
                    Log.d(TAG, "Format: ${sampleRate}Hz, $channelCount channels, $totalFrames frames")
                    
                    // Initialize MediaCodec
                    val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
                    val dec = MediaCodec.createDecoderByType(mime)
                    decoder = dec
                    dec.configure(audioFormat, null, null, 0)
                    dec.start()
                    
                    // Create ring buffer (3 seconds capacity)
                    ringBuffer = CircularPcmBuffer(sampleRate * 3)
                    
                    // Start background decode job from beginning
                    startDecodeJob(0)
                    
                    // Wait briefly for initial buffer fill
                    delay(INITIAL_BUFFER_WAIT_MS)
                    
                    isPrepared = true
                    Log.d(TAG, "Preparation complete, buffer has ${ringBuffer?.availableFrames()} frames")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to prepare", e)
                    cleanup()
                    throw e
                }
            }
        }
    }
    
    override suspend fun seek(frame: Long) {
        mutex.withLock {
            if (!isPrepared) throw IllegalStateException("Not prepared")
            
            val targetFrame = frame.coerceAtLeast(0)
            
            Log.d(TAG, "🎯 Seeking to frame $targetFrame")
            
            try {
                // Cancel current decode job
                decodeJob?.cancel()
                decodeJob = null
                
                // Clear ring buffer
                ringBuffer?.clear()
                
                // Convert frame to microseconds
                val timeUs = (targetFrame * 1_000_000L) / sampleRate
                
                // Seek extractor
                extractor?.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                
                // Flush decoder to clear old buffers
                decoder?.flush()
                
                // Reset state
                isEOS = false
                currentDecodeFrame = targetFrame
                
                // Set ring buffer read position
                ringBuffer?.setReadPosition(targetFrame)
                
                // Start new decode job from seek position
                startDecodeJob(targetFrame)
                
                // Wait briefly for buffer to fill
                delay(50)
                
                Log.d(TAG, "✅ Seek complete, buffer has ${ringBuffer?.availableFrames()} frames")
                
            } catch (e: Exception) {
                Log.e(TAG, "Seek failed", e)
                throw e
            }
        }
    }
    
    override suspend fun readFrames(frameCount: Int): ShortArray {
        if (!isPrepared) throw IllegalStateException("Not prepared")
        
        val buffer = ringBuffer ?: return ShortArray(0)
        
        // Read from ring buffer (non-blocking)
        val samples = buffer.read(frameCount)
        
        if (samples.size < frameCount * 2) {
            // Buffer underrun - log warning
            val framesRead = samples.size / 2
            Log.w(TAG, "⚠️ Buffer underrun: requested $frameCount frames, got $framesRead (available=${buffer.availableFrames()})")
            
            // Check buffer health
            if (!buffer.isHealthy(sampleRate)) {
                Log.w(TAG, "⚠️ Buffer unhealthy, available=${buffer.availableFrames()} frames")
            }
        }
        
        return samples
    }
    
    /**
     * Start background decode job that continuously fills the ring buffer.
     */
    private fun startDecodeJob(startFrame: Long) {
        decodeJob?.cancel()
        
        currentDecodeFrame = startFrame
        
        decodeJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "🔄 Decode job started from frame $startFrame")
            
            while (isActive && !isEOS) {
                val buffer = ringBuffer
                if (buffer == null) {
                    Log.e(TAG, "❌ Ring buffer is null, stopping decode job")
                    break
                }
                
                // Check if buffer has space
                if (buffer.spaceAvailableFrames() > 1024) {
                    // Decode next chunk
                    val chunk = decodeChunk()
                    
                    if (chunk.isNotEmpty()) {
                        val framesWritten = buffer.write(chunk, currentDecodeFrame)
                        currentDecodeFrame += framesWritten.toLong()
                        
                        // Log buffer health periodically
                        if (currentDecodeFrame % (sampleRate * 2) == 0L) {
                            Log.d(TAG, "📊 Buffer: available=${buffer.availableFrames()}, space=${buffer.spaceAvailableFrames()}, healthy=${buffer.isHealthy(sampleRate)}")
                        }
                    } else if (isEOS) {
                        Log.d(TAG, "⏹️ Decode job reached EOS")
                        break
                    }
                } else {
                    // Buffer full, back off
                    delay(BUFFER_BACKOFF_MS)
                }
            }
            
            Log.d(TAG, "🛑 Decode job stopped")
        }
    }
    
    /**
     * Decode a chunk of audio from MediaCodec.
     * Returns decoded stereo PCM16 data, or empty array if no data available.
     */
    private suspend fun decodeChunk(): ShortArray {
        val ext = extractor ?: return ShortArray(0)
        val dec = decoder ?: return ShortArray(0)
        
        coroutineContext.ensureActive()
        
        try {
            // Feed input buffer
            val inputIndex = dec.dequeueInputBuffer(DECODE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = dec.getInputBuffer(inputIndex)!!
                val sampleSize = ext.readSampleData(inputBuffer, 0)
                
                if (sampleSize < 0) {
                    Log.d(TAG, "⏹️ No more input data, queuing EOS")
                    dec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    dec.queueInputBuffer(inputIndex, 0, sampleSize, ext.sampleTime, 0)
                    ext.advance()
                }
            }
            
            // Retrieve output buffer
            val bufferInfo = MediaCodec.BufferInfo()
            val outputIndex = dec.dequeueOutputBuffer(bufferInfo, DECODE_TIMEOUT_US)
            
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = dec.getOutputBuffer(outputIndex)!!
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    
                    val shortCount = bufferInfo.size / 2
                    val decodedShorts = ShortArray(shortCount)
                    outputBuffer.asShortBuffer().get(decodedShorts)
                    
                    dec.releaseOutputBuffer(outputIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "⏹️ Output EOS flag detected")
                        isEOS = true
                    }
                    
                    // Handle mono -> stereo conversion
                    return if (channelCount == 1) {
                        // Duplicate each sample for stereo
                        ShortArray(shortCount * 2) { i ->
                            decodedShorts[i / 2]
                        }
                    } else {
                        decodedShorts
                    }
                }
                
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = dec.outputFormat
                    Log.d(TAG, "ℹ️ Output format changed: $newFormat")
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    // Try again immediately
                    return decodeChunk()
                }
                
                else -> {
                    // No data available yet
                    return ShortArray(0)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decode chunk failed", e)
            isEOS = true
            return ShortArray(0)
        }
    }
    
    override fun release() {
        cleanup()
    }
    
    private fun cleanup() {
        // Cancel decode job
        decodeJob?.cancel()
        decodeJob = null
        
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoder", e)
        }
        
        try {
            extractor?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing extractor", e)
        }
        
        decoder = null
        extractor = null
        ringBuffer = null
        isPrepared = false
    }
}
