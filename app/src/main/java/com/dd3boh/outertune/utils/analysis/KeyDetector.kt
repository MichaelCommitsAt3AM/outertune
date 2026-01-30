package com.dd3boh.outertune.utils.analysis

import android.util.Log

/**
 * Musical key detection using libKeyFinder.
 * 
 * Detects the musical key of audio tracks for harmonic mixing and DJ features.
 */
object KeyDetector {
    private const val TAG = "KeyDetector"
    
    private const val MIN_DURATION_SECONDS = 5f
    private const val ANALYSIS_WINDOW_SECONDS = 60
    
    init {
        try {
            System.loadLibrary("outertune-analysis")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }
    
    /**
     * Detects the musical key of audio PCM data.
     * 
     * @param pcmData Mono float PCM samples (-1.0 to 1.0)
     * @param sampleRate Sample rate (e.g., 44100)
     * @return MusicalKey or null if detection fails or confidence is low
     */
    fun detectKey(pcmData: FloatArray, sampleRate: Int): MusicalKey? {
        if (pcmData.isEmpty()) {
            Log.w(TAG, "Empty PCM data")
            return null
        }
        
        val durationSeconds = pcmData.size.toFloat() / sampleRate
        if (durationSeconds < MIN_DURATION_SECONDS) {
            Log.w(TAG, "Track too short for reliable key detection: ${durationSeconds}s")
            return null
        }
        
        // Use a window of audio (max 60 seconds from the middle)
        val windowPcm = extractAnalysisWindow(pcmData, sampleRate)
        
        // Check if the window is mostly silence
        if (isSilence(windowPcm)) {
            Log.d(TAG, "Audio window contains mostly silence")
            return null
        }
        
        val camelotIndex = detectKeyNative(windowPcm, sampleRate)
        
        if (camelotIndex < 1) {
            Log.d(TAG, "Key detection returned low confidence or silence")
            return null
        }
        
        val key = MusicalKey.fromCamelotIndex(camelotIndex)
        
        if (key != null) {
            Log.i(TAG, "Detected key: ${key.camelot} (${key.toDisplayString()})")
        }
        
        return key
    }
    
    /**
     * Extracts a 30-60 second window from the middle of the track.
     * Skips intro/outro which may have different key signatures or silence.
     */
    private fun extractAnalysisWindow(pcmData: FloatArray, sampleRate: Int): FloatArray {
        val durationSeconds = pcmData.size.toFloat() / sampleRate
        
        // For short tracks, use everything
        if (durationSeconds <= ANALYSIS_WINDOW_SECONDS) {
            return pcmData
        }
        
        // For longer tracks, use 60 seconds from the middle
        val windowSamples = ANALYSIS_WINDOW_SECONDS * sampleRate
        val startOffset = ((durationSeconds / 2f) - (ANALYSIS_WINDOW_SECONDS / 2f)) * sampleRate
        val startIndex = startOffset.toInt().coerceAtLeast(0)
        val endIndex = (startIndex + windowSamples).coerceAtMost(pcmData.size)
        
        return pcmData.copyOfRange(startIndex, endIndex)
    }
    
    /**
     * Checks if audio data is mostly silence.
     * Uses RMS energy threshold.
     */
    private fun isSilence(pcmData: FloatArray): Boolean {
        if (pcmData.isEmpty()) return true
        
        var sumSquares = 0.0
        for (sample in pcmData) {
            sumSquares += sample * sample
        }
        
        val rms = kotlin.math.sqrt(sumSquares / pcmData.size)
        
        // Threshold for silence (very quiet audio)
        val silenceThreshold = 0.001f
        
        return rms < silenceThreshold
    }
    
    /**
     * Native method to detect key using libKeyFinder.
     * 
     * @param pcmData Mono float PCM samples
     * @param sampleRate Sample rate in Hz
     * @return Camelot index (1-24) or -1 for failure/silence
     */
    private external fun detectKeyNative(pcmData: FloatArray, sampleRate: Int): Int
}
