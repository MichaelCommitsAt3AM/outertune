package com.dd3boh.outertune.utils.analysis

import kotlin.math.roundToLong

object BeatGridNormalizer {

    /**
     * @param detectedGrid - Raw timestamps from BTrack + refinement (in seconds)
     * @param bpm - Detected BPM
     * @param durationSec - Exact track duration
     * @return Normalized grid where beats are at perfect intervals
     */
    fun normalize(
        detectedGrid: List<Float>,
        bpm: Float,
        durationSec: Float
    ): List<Float> {
        if (detectedGrid.isEmpty() || bpm <= 0 || durationSec <= 0) {
            return emptyList()
        }

        // 1. Calculate the PERFECT beat interval
        val perfectInterval = 60f / bpm  // e.g., 0.46875s for 128 BPM

        // 2. Use the FIRST detected beat as the "anchor" (phase)
        // This preserves the track's natural feel
        val firstBeat = detectedGrid.first()

        // 3. Generate a perfect grid forward from the anchor
        val normalizedGrid = mutableListOf<Float>()

        // Add beats BEFORE the first detected beat (if it starts late)
        var currentBeat = firstBeat
        while (currentBeat >= perfectInterval) {
            currentBeat -= perfectInterval
            normalizedGrid.add(0, currentBeat)
        }

        // Add the anchor and all forward beats
        currentBeat = firstBeat
        while (currentBeat < durationSec) {
            normalizedGrid.add(currentBeat)
            currentBeat += perfectInterval
        }

        return normalizedGrid
    }

    /**
     * Alternative: Use MEDIAN of all intervals for more stable results
     * if BTrack's BPM detection is slightly off.
     */
    fun normalizeWithMedianInterval(
        detectedGrid: List<Float>,
        durationSec: Float
    ): List<Float> {
        if (detectedGrid.size < 3 || durationSec <= 0) {
            return detectedGrid
        }

        // Calculate all intervals
        val intervals = detectedGrid.zipWithNext { a, b -> b - a }

        // Use median interval (more robust than BPM alone)
        val medianInterval = intervals.sorted()[intervals.size / 2]

        val firstBeat = detectedGrid.first()
        val normalizedGrid = mutableListOf<Float>()

        // Backfill
        var currentBeat = firstBeat
        while (currentBeat >= medianInterval) {
            currentBeat -= medianInterval
            normalizedGrid.add(0, currentBeat)
        }

        // Forward fill
        currentBeat = firstBeat
        while (currentBeat < durationSec) {
            normalizedGrid.add(currentBeat)
            currentBeat += medianInterval
        }

        return normalizedGrid
    }

    /**
     * HYBRID APPROACH (Recommended):
     * Keep the detected grid for VISUALIZATION (shows actual transients),
     * but use normalized grid for PLAYBACK SYNC.
     */
    data class DualGrid(
        val visual: List<Float>,      // Irregular, snapped to transients
        val sync: List<Float>          // Perfect mathematical grid
    )

    fun createDualGrid(
        detectedGrid: List<Float>,
        bpm: Float,
        durationSec: Float
    ): DualGrid {
        return DualGrid(
            visual = detectedGrid,
            sync = normalize(detectedGrid, bpm, durationSec)
        )
    }
}

/**
 * Extension to convert normalized grid back to milliseconds for storage
 */
fun List<Float>.toMilliseconds(): LongArray {
    return this.map { (it * 1000).roundToLong() }.toLongArray()
}