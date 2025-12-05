package com.dd3boh.outertune.utils.analysis

import android.util.Log

object AudioAnalyzer {
    private const val TAG = "AudioAnalyzer"

    init {
        try {
            System.loadLibrary("outertune-analysis")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    /**
     * Analyzes raw PCM data to find BPM and First Beat.
     * @param pcmData Float array of audio samples (-1.0 to 1.0)
     * @param sampleRate Sample rate (e.g., 44100)
     * @return FloatArray where [0] is BPM, [1] is First Beat Timestamp (ms)
     */
    external fun analyzeBpm(pcmData: FloatArray, sampleRate: Int): FloatArray?
}