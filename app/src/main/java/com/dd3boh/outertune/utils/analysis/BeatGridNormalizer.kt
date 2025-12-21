package com.dd3boh.outertune.utils.analysis

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Utilities for producing stable, phase-preserving beat grids suitable for DJ sync.
 *
 * Key decisions:
 * - Normalize() produces a mathematically perfect sync grid anchored to the first detected beat.
 * - promoteGrid() subdivides beats (never re-analyzes audio) and uses an improved decision rule
 *   to determine promotion factors (handles 100 -> 135 as ×2 when analysis is ambiguous).
 * - resolveDjGrids() returns a DualGrid(visual, sync) where promoting the visual grid is optional.
 */
object BeatGridNormalizer {

    // These ranges should remain consistent with BpmUtils
    private val AMBIGUITY_RANGE = 80f..110f
    private val DJ_PREFERRED_RANGE = 120f..160f

    /**
     * @param detectedGrid - Raw timestamps from BTrack + refinement (in seconds)
     * @param bpm - Detected BPM (analysisBpm or displayBpm depending on usage)
     * @param durationSec - Exact track duration in seconds
     * @return Normalized (mathematical) grid where beats are at perfect intervals,
     * anchored to the first detected beat (phase preserved).
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

        // Backfill before the first beat (guard against adding negative timestamps)
        var currentBeat = firstBeat
        while (currentBeat - perfectInterval >= 0f) {
            currentBeat -= perfectInterval
            normalizedGrid.add(0, currentBeat)
        }

        // Forward fill from anchor to track end
        currentBeat = firstBeat
        while (currentBeat <= durationSec) {
            normalizedGrid.add(currentBeat)
            currentBeat += perfectInterval
        }

        return normalizedGrid
    }

    /**
     * Normalize with separation into sections (useful for tracks with long breakdowns/silences).
     * threshold: gap in seconds that indicates a new section.
     */
    fun normalizeWithSections(detectedGrid: List<Float>, durationSec: Float, threshold: Float = 2.0f): List<Float> {
        if (detectedGrid.isEmpty() || durationSec <= 0f) return emptyList()

        val normalizedGrid = mutableListOf<Float>()

        // Group beats into sections separated by > threshold seconds
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

        // Normalize each section independently
        sections.forEachIndexed { index, section ->
            if (section.isEmpty()) return@forEachIndexed

            val localBpm = calculateBpmFor(section)
            val perfectInterval = 60f / localBpm

            val startBeat = section.first()
            val endBeat = section.last()

            // Backfill only for the very first section, avoid negatives
            var beat = startBeat
            if (index == 0) {
                while (beat - perfectInterval >= 0f) {
                    beat -= perfectInterval
                    normalizedGrid.add(0, beat)
                }
                beat = startBeat
            }

            // Forward fill within or slightly past the section end (tolerance half interval)
            while (beat <= endBeat + (perfectInterval * 0.5f)) {
                normalizedGrid.add(beat)
                beat += perfectInterval
            }
        }

        // Ensure sorted and unique
        return normalizedGrid.sorted().distinct()
    }

    private fun calculateBpmFor(section: List<Float>): Float {
        if (section.size < 2) return 120f
        val duration = section.last() - section.first()
        val intervals = section.size - 1
        val avgInterval = duration / intervals
        return if (avgInterval > 0f) 60f / avgInterval else 120f
    }

    /**
     * Alternative normalization using median interval (more robust to outliers).
     */
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

        // Backfill
        var currentBeat = firstBeat
        while (currentBeat - medianInterval >= 0f) {
            currentBeat -= medianInterval
            normalizedGrid.add(0, currentBeat)
        }

        // Forward fill
        currentBeat = firstBeat
        while (currentBeat <= durationSec) {
            normalizedGrid.add(currentBeat)
            currentBeat += medianInterval
        }

        return normalizedGrid
    }

    /**
     * Promote an existing (mathematical) grid by subdividing beats.
     *
     * Important: Promotion is purely mathematical (no audio re-analysis) and preserves phase.
     * The promotion factor is determined by:
     * - If analysisBpm is ambiguous (80-110) AND displayBpm falls in DJ preferred range (120-160),
     *   treat as a double-time promotion (factor = 2).
     * - Otherwise, decide based on ratio thresholds and rounding fallbacks.
     *
     * Note: factor <= 1 -> no promotion.
     */
    fun promoteGrid(
        originalGrid: List<Float>,
        analysisBpm: Float,
        displayBpm: Float
    ): List<Float> {
        if (originalGrid.isEmpty() || analysisBpm <= 0f || displayBpm <= 0f) return originalGrid

        // Defensive: if equal or nearly equal, no promotion
        if (kotlin.math.abs(displayBpm - analysisBpm) < 0.0001f) return originalGrid

        // Primary rule: ambiguous analysis + DJ-preferred display => promote x2
        val isAmbiguous = analysisBpm in AMBIGUITY_RANGE
        val displayInDjPreferred = displayBpm in DJ_PREFERRED_RANGE

        val factor = when {
            // Explicit ambiguous -> DJ-preferred mapping (handles 100 -> 135)
            isAmbiguous && displayInDjPreferred -> 2

            // If display is substantially larger than analysis, pick integer factor using thresholds
            else -> {
                val ratio = displayBpm / analysisBpm

                // thresholds chosen to map clear divisors:
                // ratio >= 3.5 => factor 4 (e.g., near 4x)
                // ratio >= 1.75 => factor 2 (near 2x)
                // fallback: round ratio to nearest integer (but at least 1)
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

        // ensure last beat is included
        promoted.add(originalGrid.last())
        return promoted
    }

    /**
     * Dual grid container with visual and sync grids.
     * - visual: kept close to transients (can be raw detected or promoted)
     * - sync: mathematically perfect grid used for playback/sync
     */
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

    /**
     * Resolve final DJ grids.
     *
     * @param detectedGrid raw detected grid (snapped to transients)
     * @param analysisBpm raw detected bpm
     * @param displayBpm dj/display bpm (may be same or promoted)
     * @param durationSec track duration in seconds
     * @param promoteVisual whether to mathematically promote the visual grid.
     *                      Default: false (keep visual transients untouched).
     */
    fun resolveDjGrids(
        detectedGrid: List<Float>,
        analysisBpm: Float,
        displayBpm: Float,
        durationSec: Float,
        promoteVisual: Boolean = false
    ): DualGrid {
        // 1. Base sync grid at analysis BPM (perfect intervals anchored to first beat)
        val baseSyncGrid = normalize(detectedGrid, analysisBpm, durationSec)

        // 2. Promote sync grid if needed (subdivision)
        val finalSyncGrid = promoteGrid(baseSyncGrid, analysisBpm, displayBpm)

        // 3. Decide visual grid: either keep raw detected (preserves transients) or promote for UI alignment
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

/**
 * Extension: convert seconds to rounded milliseconds for storage.
 */
fun List<Float>.toMilliseconds(): LongArray {
    return this.map { (it * 1000f).roundToLong() }.toLongArray()
}
