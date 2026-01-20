package com.dd3boh.outertune.transition.playback

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe circular buffer for stereo PCM16 audio data.
 * 
 * Designed for producer-consumer pattern where a background decoder thread
 * writes decoded audio chunks and a real-time playback thread reads samples.
 * 
 * Features:
 * - Fixed capacity to prevent unbounded memory growth
 * - Atomic pointers for lock-free availability checks
 * - Frame-accurate position tracking for seeking
 * 
 * @param capacityFrames Maximum number of stereo frames to buffer
 */
class CircularPcmBuffer(
    capacityFrames: Int
) {
    companion object {
        private const val TAG = "CircularPcmBuffer"
    }
    
    // Internal storage (stereo samples = 2 shorts per frame)
    private val buffer = ShortArray(capacityFrames * 2)
    private val capacityFrames = capacityFrames
    
    // Atomic pointers for thread-safe operations
    private val writePointer = AtomicLong(0)  // Frame position
    private val readPointer = AtomicLong(0)   // Frame position
    
    // Mutex for actual data copy operations
    private val writeMutex = Mutex()
    private val readMutex = Mutex()
    
    // Track absolute frame positions in source audio
    private var readFramePosition: Long = 0
    private var writeFramePosition: Long = 0
    
    /**
     * Returns number of frames available for reading.
     * Thread-safe, lock-free.
     */
    fun availableFrames(): Long {
        val write = writePointer.get()
        val read = readPointer.get()
        return write - read
    }
    
    /**
     * Returns number of frames available for writing.
     * Thread-safe, lock-free.
     */
    fun spaceAvailableFrames(): Long {
        val available = availableFrames()
        return capacityFrames - available
    }
    
    /**
     * Write stereo PCM samples to the buffer.
     * 
     * @param samples Interleaved stereo PCM16 data (L, R, L, R...)
     * @param sourceFramePosition Absolute frame position in source audio
     * @return Number of frames actually written (may be less if buffer full)
     */
    suspend fun write(samples: ShortArray, sourceFramePosition: Long): Int {
        if (samples.isEmpty()) return 0
        
        val frames = samples.size / 2
        val space = spaceAvailableFrames()
        
        if (space <= 0) {
            Log.w(TAG, "Buffer full, cannot write $frames frames")
            return 0
        }
        
        val framesToWrite = minOf(frames.toLong(), space).toInt()
        
        writeMutex.withLock {
            val writePos = writePointer.get()
            val bufferIndex = (writePos % capacityFrames).toInt()
            
            // Handle wrap-around
            val remainingFrames = capacityFrames - bufferIndex
            
            if (framesToWrite <= remainingFrames) {
                // Simple case: no wrap-around
                System.arraycopy(samples, 0, buffer, bufferIndex * 2, framesToWrite * 2)
            } else {
                // Wrap-around case: write in two chunks
                val firstChunkFrames = remainingFrames
                val secondChunkFrames = framesToWrite - firstChunkFrames
                
                System.arraycopy(samples, 0, buffer, bufferIndex * 2, firstChunkFrames * 2)
                System.arraycopy(samples, firstChunkFrames * 2, buffer, 0, secondChunkFrames * 2)
            }
            
            writePointer.addAndGet(framesToWrite.toLong())
            writeFramePosition = sourceFramePosition + framesToWrite
            
            Log.v(TAG, "Wrote $framesToWrite frames, available=${availableFrames()}, space=${spaceAvailableFrames()}")
        }
        
        return framesToWrite
    }
    
    /**
     * Read stereo PCM samples from the buffer.
     * 
     * @param frameCount Number of stereo frames to read
     * @return Interleaved stereo PCM16 data (may be shorter if insufficient data)
     */
    suspend fun read(frameCount: Int): ShortArray {
        val available = availableFrames()
        
        if (available <= 0) {
            Log.w(TAG, "Buffer empty, cannot read $frameCount frames")
            return ShortArray(0)
        }
        
        val framesToRead = minOf(frameCount.toLong(), available).toInt()
        val result = ShortArray(framesToRead * 2)
        
        readMutex.withLock {
            val readPos = readPointer.get()
            val bufferIndex = (readPos % capacityFrames).toInt()
            
            // Handle wrap-around
            val remainingFrames = capacityFrames - bufferIndex
            
            if (framesToRead <= remainingFrames) {
                // Simple case: no wrap-around
                System.arraycopy(buffer, bufferIndex * 2, result, 0, framesToRead * 2)
            } else {
                // Wrap-around case: read in two chunks
                val firstChunkFrames = remainingFrames
                val secondChunkFrames = framesToRead - firstChunkFrames
                
                System.arraycopy(buffer, bufferIndex * 2, result, 0, firstChunkFrames * 2)
                System.arraycopy(buffer, 0, result, firstChunkFrames * 2, secondChunkFrames * 2)
            }
            
            readPointer.addAndGet(framesToRead.toLong())
            readFramePosition += framesToRead
            
            Log.v(TAG, "Read $framesToRead frames, available=${availableFrames()}, space=${spaceAvailableFrames()}")
        }
        
        return result
    }
    
    /**
     * Clear all buffered data and reset pointers.
     * Should be called when seeking to a new position.
     */
    suspend fun clear() {
        writeMutex.withLock {
            readMutex.withLock {
                val write = writePointer.get()
                readPointer.set(write)  // Fast-forward read to write position
                Log.d(TAG, "Buffer cleared, pointers reset")
            }
        }
    }
    
    /**
     * Set the read position for seeking.
     * Clears the buffer and sets up for reading from a new position.
     * 
     * @param framePosition Absolute frame position in source audio
     */
    suspend fun setReadPosition(framePosition: Long) {
        writeMutex.withLock {
            readMutex.withLock {
                writePointer.set(0)
                readPointer.set(0)
                readFramePosition = framePosition
                writeFramePosition = framePosition
                Log.d(TAG, "Read position set to frame $framePosition")
            }
        }
    }
    
    /**
     * Get current absolute read position in source audio.
     */
    fun getReadFramePosition(): Long = readFramePosition
    
    /**
     * Check if buffer health is good (has enough data for smooth playback).
     * Returns true if buffer has at least 500ms of audio.
     */
    fun isHealthy(sampleRate: Int): Boolean {
        val minHealthyFrames = sampleRate / 2  // 500ms
        return availableFrames() >= minHealthyFrames
    }
}
