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

    fun normalizeWithSections(detectedGrid: List<Float>, threshold: Float = 2.0f): List<Float> {
        if (detectedGrid.isEmpty()) return emptyList()

        val normalizedGrid = mutableListOf<Float>()

        // 1. Group beats into "sections" separated by silence/gaps
        val sections = mutableListOf<MutableList<Float>>()
        var currentSection = mutableListOf<Float>()

        // Always add the first beat
        currentSection.add(detectedGrid.first())

        detectedGrid.zipWithNext { current, next ->
            // If the gap is huge (e.g. breakdown/silence), Start NEW Section
            if (next - current > threshold) {
                sections.add(currentSection)
                currentSection = mutableListOf()
            }
            currentSection.add(next)
        }
        sections.add(currentSection) // Add the final section

        // 2. Normalize each section individually
        sections.forEachIndexed { index, section ->
            if (section.isNotEmpty()) {
                val localBpm = calculateBpmFor(section)
                val perfectInterval = 60f / localBpm

                // For the very first section, we might want to backfill to 0.0s
                // For later sections, we strictly stick to the start/end of the section
                val startBeat = section.first()
                val endBeat = section.last()

                var beat = startBeat

                // Backfill logic (Only for the very first section)
                if (index == 0) {
                    while (beat >= perfectInterval) {
                        beat -= perfectInterval
                        normalizedGrid.add(0, beat)
                    }
                    beat = startBeat // Reset to start forward fill
                }

                // Forward fill logic
                while (beat <= endBeat + (perfectInterval * 0.5f)) {
                    normalizedGrid.add(beat)
                    beat += perfectInterval
                }
            }
        }

        return normalizedGrid.sorted().distinct()
    }

    private fun calculateBpmFor(section: List<Float>): Float {
        if (section.size < 2) return 120f // Fallback
        val duration = section.last() - section.first()
        val intervals = section.size - 1
        val avgInterval = duration / intervals
        return if (avgInterval > 0) 60f / avgInterval else 120f
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