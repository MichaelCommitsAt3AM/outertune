package com.dd3boh.outertune.transition.playback

/**
 * Interface for windowed PCM audio access.
 * 
 * Provides streaming, seekable access to decoded PCM audio data without
 * requiring full-track decoding into RAM.
 * 
 * All audio is returned as interleaved stereo PCM16 (L, R, L, R...).
 */
interface PcmSource {
    /**
     * Sample rate of the decoded audio in Hz.
     * Valid after prepare() is called.
     */
    val sampleRate: Int
    
    /**
     * Total number of stereo frames in the audio.
     * Valid after prepare() is called.
     * Note: 1 frame = 2 samples (L + R)
     */
    val totalFrames: Long
    
    /**
     * Initialize the decoder and determine audio format.
     * Must be called before any other operations.
     * 
     * @throws Exception if initialization fails
     */
    suspend fun prepare()
    
    /**
     * Seek to a specific frame position.
     * 
     * @param frame The stereo frame index to seek to (0-based)
     * @throws Exception if seek fails
     */
    suspend fun seek(frame: Long)
    
    /**
     * Read up to frameCount stereo frames from the current position.
     * 
     * Returns an interleaved ShortArray (L, R, L, R...).
     * The returned array size may be less than frameCount * 2 if:
     * - Near end of file
     * - Temporary decode unavailability
     * 
     * @param frameCount Maximum number of stereo frames to read
     * @return ShortArray of interleaved stereo samples (size <= frameCount * 2)
     */
    suspend fun readFrames(frameCount: Int): ShortArray
    
    /**
     * Release all resources (MediaExtractor, MediaCodec, caches).
     * After calling release(), the source cannot be used anymore.
     */
    fun release()
}
