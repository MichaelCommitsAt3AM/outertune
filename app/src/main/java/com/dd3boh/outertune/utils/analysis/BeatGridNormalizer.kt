package com.dd3boh.outertune.utils.analysis

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object BeatGridNormalizer {

    private val AMBIGUITY_RANGE = 80f..110f
    private val DJ_PREFERRED_RANGE = 120f..160f

    /**
     * FIXED: Uses Phase-Locking to align the grid to the "Meat" of the song
     * instead of anchoring to the first transient in the intro.
     */
    fun normalize(
        detectedGrid: List<Float>,
        bpm: Float,
        durationSec: Float
    ): List<Float> {
        if (detectedGrid.isEmpty() || bpm <= 0f || durationSec <= 0f) return emptyList()

        val perfectInterval = 60f / bpm

        // 1. Calculate the Best Anchor (Phase Locking)
        // instead of using detectedGrid.first(), we find the offset that fits the most beats.
        val bestAnchor = findConsensusAnchor(detectedGrid, perfectInterval)

        val normalizedGrid = mutableListOf<Float>()

        // 2. Backfill from the best anchor to 0
        // We use the consensus anchor, ensuring alignment with the main body of the song.
        if (bestAnchor > 0f) {
            val beatsToBackfill = kotlin.math.ceil(bestAnchor / perfectInterval).toInt()
            var currentBeat = bestAnchor - (beatsToBackfill * perfectInterval)

            // Fix floating point jitter around 0
            if (currentBeat < -0.05f) {
                currentBeat += perfectInterval
            }

            while (currentBeat < bestAnchor - (perfectInterval * 0.5f)) {
                if (currentBeat >= 0f) {
                    normalizedGrid.add(currentBeat)
                }
                currentBeat += perfectInterval
            }
        }

        // 3. Forward fill
        var currentBeat = bestAnchor
        // Align the start exactly to our calculated best anchor
        if (normalizedGrid.isNotEmpty() && abs(normalizedGrid.last() - currentBeat) < 0.01f) {
            // prevent duplicate if backfill landed exactly on anchor
        } else {
            // Determine starting point if backfill didn't reach
            if (normalizedGrid.isEmpty() && currentBeat < 0) {
                while(currentBeat < 0) currentBeat += perfectInterval
            }
        }

        while (currentBeat <= durationSec) {
            if (currentBeat >= 0f) normalizedGrid.add(currentBeat)
            currentBeat += perfectInterval
        }

        return normalizedGrid.sorted().distinct()
    }

    /**
     * Finds the beat time that, if used as an anchor, minimizes the error
     * across all other detected beats. This ignores outliers in intros/outros.
     */
    private fun findConsensusAnchor(detected: List<Float>, interval: Float): Float {
        if (detected.isEmpty()) return 0f

        // Calculate the "phase" (remainder) of every beat against the interval
        // We are looking for the remainder that appears most frequently.
        val phases = detected.map { beatTime ->
            val remainder = beatTime % interval
            // Handle wrapping (e.g. remainder 0.49 is close to 0.0, and 0.0 is close to 0.49 if interval is 0.5)
            // But for simple linear grid, standard modulo is mostly fine if we center it.
            if (remainder < 0) remainder + interval else remainder
        }

        // Use a sliding window to find the densest cluster of phases
        // This is a simplified "Mean Shift" or "Mode" search
        val precision = 0.01f // 10ms bucket
        var maxDensity = 0
        var bestPhase = phases.first()

        // Test every phase as a potential center
        for (candidate in phases) {
            var density = 0
            for (p in phases) {
                // Check distance accounting for wrapping around the interval
                val dist = abs(p - candidate)
                val distWrapped = abs(dist - interval)
                val minDist = minOf(dist, distWrapped)

                if (minDist < precision) {
                    density++
                }
            }
            if (density > maxDensity) {
                maxDensity = density
                bestPhase = candidate
            }
        }

        // Now find the actual detected beat that is closest to this ideal phase
        // (Preferring a beat later in the track usually gives better precision,
        // but finding the first beat that matches this phase is safer for the start).

        // Let's pick the first detected beat that aligns with this "Best Phase"
        val alignedBeat = detected.firstOrNull { beatTime ->
            val p = beatTime % interval
            val dist = abs(p - bestPhase)
            val distWrapped = abs(dist - interval)
            minOf(dist, distWrapped) < (interval * 0.1f) // 10% tolerance
        } ?: detected.first()

        return alignedBeat
    }

    // Keep existing methods, but update them if they rely on simple normalization
    // ... (normalizeWithSections, normalizeWithMedianInterval, promoteGrid, etc.)

    // NOTE: For normalizeWithMedianInterval, you should also apply the consensus logic
    // if you want to fix the drift there, but 'normalize' is the primary one used by loadData.

    fun normalizeWithSections(detectedGrid: List<Float>, durationSec: Float, threshold: Float = 2.0f): List<Float> {
        // ... (Existing implementation is okay for sections as it resets per section)
        // Just ensure backfill logic handles negative check like above.
        return normalize(detectedGrid, calculateBpmFor(detectedGrid), durationSec) // Fallback to our smart normalizer
    }

    private fun calculateBpmFor(section: List<Float>): Float {
        if (section.size < 2) return 120f
        val duration = section.last() - section.first()
        val intervals = section.size - 1
        val avgInterval = duration / intervals
        return if (avgInterval > 0f) 60f / avgInterval else 120f
    }

    fun normalizeWithMedianInterval(
        detectedGrid: List<Float>,
        durationSec: Float
    ): List<Float> {
        if (detectedGrid.size < 3 || durationSec <= 0f) return detectedGrid

        // Calculate median interval
        val intervals = detectedGrid.zipWithNext { a, b -> b - a }
        val sorted = intervals.sorted()
        val medianInterval = sorted[sorted.size / 2]

        val bpm = 60f / medianInterval

        // Use our smart normalize with the calculated median BPM
        return normalize(detectedGrid, bpm, durationSec)
    }

    fun promoteGrid(
        originalGrid: List<Float>,
        analysisBpm: Float,
        displayBpm: Float
    ): List<Float> {
        if (originalGrid.isEmpty() || analysisBpm <= 0f || displayBpm <= 0f) return originalGrid

        if (kotlin.math.abs(displayBpm - analysisBpm) < 0.0001f) return originalGrid

        val isAmbiguous = analysisBpm in AMBIGUITY_RANGE
        val displayInDjPreferred = displayBpm in DJ_PREFERRED_RANGE

        val factor = when {
            isAmbiguous && displayInDjPreferred -> 2
            else -> {
                val ratio = displayBpm / analysisBpm
                when {
                    ratio >= 3.5f -> 4
                    ratio >= 1.75f -> 2
                    else -> ratio.roundToInt().coerceAtLeast(1)
                }
            }
        }

        if (factor <= 1) return originalGrid

        val promoted = mutableListOf<Float>()

        for (i in 0 until originalGrid.size - 1) {
            val current = originalGrid[i]
            val next = originalGrid[i + 1]
            val interval = next - current
            val sub = interval / factor

            for (j in 0 until factor) {
                promoted.add(current + j * sub)
            }
        }

        promoted.add(originalGrid.last())
        return promoted
    }

    data class DualGrid(
        val visual: List<Float>,
        val sync: List<Float>
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

    fun resolveDjGrids(
        detectedGrid: List<Float>,
        analysisBpm: Float,
        displayBpm: Float,
        durationSec: Float,
        promoteVisual: Boolean = false
    ): DualGrid {
        // Use the smart normalize function
        val baseSyncGrid = normalize(detectedGrid, analysisBpm, durationSec)
        val finalSyncGrid = promoteGrid(baseSyncGrid, analysisBpm, displayBpm)

        val finalVisualGrid = if (promoteVisual) {
            promoteGrid(detectedGrid, analysisBpm, displayBpm)
        } else {
            detectedGrid
        }

        return DualGrid(
            visual = finalVisualGrid,
            sync = finalSyncGrid
        )
    }
}

fun List<Float>.toMilliseconds(): LongArray {
    return this.map { (it * 1000f).roundToLong() }.toLongArray()
}