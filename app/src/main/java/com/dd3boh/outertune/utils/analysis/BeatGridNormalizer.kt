package com.dd3boh.outertune.utils.analysis

import kotlin.math.roundToInt
import kotlin.math.roundToLong

object BeatGridNormalizer {

    private val AMBIGUITY_RANGE = 80f..110f
    private val DJ_PREFERRED_RANGE = 120f..160f

    /**
     * FIXED: Now ensures all beats are >= 0, and properly covers the start of the track.
     */
    fun normalize(
        detectedGrid: List<Float>,
        bpm: Float,
        durationSec: Float
    ): List<Float> {
        if (detectedGrid.isEmpty() || bpm <= 0f || durationSec <= 0f) return emptyList()

        val perfectInterval = 60f / bpm
        val firstBeat = detectedGrid.first()

        val normalizedGrid = mutableListOf<Float>()

        // CRITICAL FIX: Backfill to 0, but never go negative
        // Calculate how many beats we need before firstBeat to reach/pass 0
        if (firstBeat > 0f) {
            val beatsToBackfill = kotlin.math.ceil(firstBeat / perfectInterval).toInt()
            var currentBeat = firstBeat - (beatsToBackfill * perfectInterval)

            // Ensure we start at or after 0
            if (currentBeat < 0f) {
                currentBeat += perfectInterval
            }

            // Add backfilled beats (all >= 0)
            while (currentBeat < firstBeat && currentBeat <= durationSec) {
                normalizedGrid.add(currentBeat.coerceAtLeast(0f))
                currentBeat += perfectInterval
            }
        }

        // Forward fill from anchor to track end
        var currentBeat = firstBeat
        while (currentBeat <= durationSec) {
            normalizedGrid.add(currentBeat)
            currentBeat += perfectInterval
        }

        return normalizedGrid.sorted().distinct()
    }

    fun normalizeWithSections(detectedGrid: List<Float>, durationSec: Float, threshold: Float = 2.0f): List<Float> {
        if (detectedGrid.isEmpty() || durationSec <= 0f) return emptyList()

        val normalizedGrid = mutableListOf<Float>()

        val sections = mutableListOf<MutableList<Float>>()
        var currentSection = mutableListOf<Float>()
        currentSection.add(detectedGrid.first())

        detectedGrid.zipWithNext { curr, next ->
            if (next - curr > threshold) {
                sections.add(currentSection)
                currentSection = mutableListOf()
            }
            currentSection.add(next)
        }
        sections.add(currentSection)

        sections.forEachIndexed { index, section ->
            if (section.isEmpty()) return@forEachIndexed

            val localBpm = calculateBpmFor(section)
            val perfectInterval = 60f / localBpm

            val startBeat = section.first()
            val endBeat = section.last()

            var beat = startBeat
            if (index == 0) {
                // FIXED: Backfill without going negative
                val beatsToBackfill = kotlin.math.ceil(startBeat / perfectInterval).toInt()
                beat = startBeat - (beatsToBackfill * perfectInterval)

                if (beat < 0f) {
                    beat += perfectInterval
                }

                while (beat < startBeat && beat <= durationSec) {
                    normalizedGrid.add(beat.coerceAtLeast(0f))
                    beat += perfectInterval
                }
                beat = startBeat
            }

            while (beat <= endBeat + (perfectInterval * 0.5f)) {
                normalizedGrid.add(beat)
                beat += perfectInterval
            }
        }

        return normalizedGrid.sorted().distinct()
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

        val intervals = detectedGrid.zipWithNext { a, b -> b - a }
        val sorted = intervals.sorted()
        val medianInterval = sorted[sorted.size / 2]

        val firstBeat = detectedGrid.first()
        val normalizedGrid = mutableListOf<Float>()

        // FIXED: Backfill without going negative
        if (firstBeat > 0f) {
            val beatsToBackfill = kotlin.math.ceil(firstBeat / medianInterval).toInt()
            var currentBeat = firstBeat - (beatsToBackfill * medianInterval)

            if (currentBeat < 0f) {
                currentBeat += medianInterval
            }

            while (currentBeat < firstBeat && currentBeat <= durationSec) {
                normalizedGrid.add(currentBeat.coerceAtLeast(0f))
                currentBeat += medianInterval
            }
        }

        // Forward fill
        var currentBeat = firstBeat
        while (currentBeat <= durationSec) {
            normalizedGrid.add(currentBeat)
            currentBeat += medianInterval
        }

        return normalizedGrid.sorted().distinct()
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