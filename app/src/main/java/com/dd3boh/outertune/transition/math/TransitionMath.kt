package com.dd3boh.outertune.transition.math

import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import kotlin.math.abs

/**
 * Pure mathematical core for transition calculations.
 *
 * Constraints:
 * - NO Android dependencies (Pure Kotlin)
 * - NO side effects
 * - Deterministic (Same inputs -> Same TransitionPlan)
 */
object TransitionMath {

    /**
     * Calculates the full playback blueprint based on the editor state.
     *
     * @param gridA High-precision grid for Track A (seconds)
     * @param gridB High-precision grid for Track B (seconds)
     * @param bpmA Display BPM of Track A
     * @param bpmB Display BPM of Track B
     * @param offsetBeatsA The visual scroll offset of Track A (in beats)
     * @param offsetBeatsB The visual scroll offset of Track B (in beats)
     * @param config User configuration (bars count, overlap mode, etc.)
     */
    fun calculatePlan(
        gridA: List<Double>,
        gridB: List<Double>,
        bpmA: Float,
        bpmB: Float,
        offsetBeatsA: Double,
        offsetBeatsB: Double,
        config: TransitionConfig
    ): TransitionPlan? {
        if (gridA.isEmpty() || gridB.isEmpty()) return null

        // 1. Calculate Synchronization (Tempo vs Interval Match)
        val syncParams = calculateSyncParameters(gridA, gridB, bpmA, bpmB)

        // 2. Calculate Transition Zone Geometry
        // The "Transition Zone" (Green Box) is defined by barsCount.
        // widthFraction determines how much of the screen the green box occupies.
        // marginFraction determines the empty space to the left of the box.

        val beatsInZone = (config.barsCount * 4).toDouble()

        // Calculate the "Visual Margin" in beats.
        // This effectively translates the center-screen offset to the start of the green box.
        val marginFraction = (1f - config.widthFraction) / 2f
        val visualMarginBeats = if (config.widthFraction > 0f) {
            (marginFraction / config.widthFraction) * beatsInZone
        } else {
            0.0
        }

        // 3. Determine Anchor Points
        // anchorBeatA: The beat index on Track A where the transition actually begins (start of green box)
        val anchorBeatA = offsetBeatsA + visualMarginBeats

        // rawAnchorBeatB: The beat index on Track B visually aligned with anchorBeatA
        val rawAnchorBeatB = offsetBeatsB + visualMarginBeats

        // internalBeatB: Adjusted for grid compression (if Interval Match was used)
        val internalBeatB = if (syncParams.gridScalar == 1.0) {
            rawAnchorBeatB
        } else {
            rawAnchorBeatB / syncParams.gridScalar
        }

        // 4. Convert to Absolute Timestamps (Milliseconds)
        // These are critical for the Database and seek operations.
        val exitPointMs = (getTimestampForBeat(gridA, anchorBeatA) * 1000).toLong()
        val entryPointMs = (getTimestampForBeat(gridB, internalBeatB) * 1000).toLong()

        // 5. Calculate Duration
        // Duration is derived from Track A's grid speed at the transition point to ensure alignment.
        val intervalA = if (gridA.size > 1) gridA[1] - gridA[0] else 0.5
        val durationMs = (beatsInZone * intervalA * 1000).toLong()

        return TransitionPlan(
            initialSpeedB = syncParams.speedMultiplier,
            gridScalarB = syncParams.gridScalar,
            anchorBeatA = anchorBeatA,
            anchorBeatB = internalBeatB,
            transitionDurationBeats = beatsInZone,
            exitPointMs = exitPointMs,
            entryPointMs = entryPointMs,
            durationMs = durationMs,
            gridA = gridA,
            gridB = gridB
        )
    }

    // --- Synchronization Logic ---

    private data class SyncParams(val speedMultiplier: Double, val gridScalar: Double)

    private fun calculateSyncParameters(
        gridA: List<Double>,
        gridB: List<Double>,
        bpmA: Float,
        bpmB: Float
    ): SyncParams {
        val bpmDiff = abs(bpmA - bpmB)

        // Threshold logic extracted from original ViewModel
        if (bpmDiff <= 15f) {
            // Tempo Sync Mode: Just speed up/slow down Track B
            val speed = if (bpmB != 0f) (bpmA / bpmB).toDouble() else 1.0
            return SyncParams(speed, 1.0)
        } else {
            // Interval Match Mode: Used for large BPM differences (e.g. 70 vs 140)
            // We match the average grid interval instead of the exact BPM.

            val avgIntervalA = if (gridA.size > 1) (gridA.last() - gridA.first()) / (gridA.size - 1) else 0.5
            val avgIntervalB = if (gridB.size > 1) (gridB.last() - gridB.first()) / (gridB.size - 1) else 0.5

            val rawRatio = if (avgIntervalA > 0 && avgIntervalB > 0) avgIntervalB / avgIntervalA else 1.0

            // Find the best integer multiplier (0.5x, 1x, 2x, 4x) to minimize distortion
            val candidates = listOf(0.5, 1.0, 1.5, 2.0, 4.0)
            val bestMultiplier = candidates.minByOrNull { k -> abs(1.0 - (rawRatio / k)) } ?: 1.0

            // speed = rawRatio / scalar
            // This allows us to map a 140BPM grid onto a 70BPM grid by treating every 2 beats of B as 1 beat of A
            return SyncParams(
                speedMultiplier = rawRatio / bestMultiplier,
                gridScalar = bestMultiplier
            )
        }
    }

    // --- Interpolation Helpers (Double Precision) ---

    /**
     * Converts a fractional beat index to a timestamp (seconds).
     * Handles extrapolation if the beat is outside the grid bounds.
     */
    fun getTimestampForBeat(grid: List<Double>, beatIndex: Double): Double {
        if (grid.isEmpty()) return 0.0
        val lastIdx = grid.size - 1

        // Extrapolate before start
        if (beatIndex < 0) {
            val avgStep = if (grid.size > 1) (grid[1] - grid[0]) else 0.5
            return (grid[0] + beatIndex * avgStep).coerceAtLeast(0.0)
        }
        // Extrapolate after end
        if (beatIndex > lastIdx) {
            val avgStep = if (grid.size > 1) (grid[lastIdx] - grid[lastIdx - 1]) else 0.5
            return grid[lastIdx] + (beatIndex - lastIdx) * avgStep
        }

        // Interpolate inside grid
        val idx = beatIndex.toInt()
        val t1 = grid[idx]
        val t2 = if (idx + 1 < grid.size) grid[idx + 1] else t1 + 0.5

        // Linear interpolation
        return t1 + (t2 - t1) * (beatIndex - idx)
    }

    /**
     * Converts a timestamp (seconds) to a fractional beat index.
     * Uses binary search for efficiency.
     */
    fun getBeatForTimestamp(grid: List<Double>, time: Double): Double {
        if (grid.isEmpty()) return 0.0

        // Binary search for the time
        val ip = grid.binarySearch(time)
        if (ip >= 0) return ip.toDouble()

        // insertion point is -(ip + 1)
        val idx = -(ip + 1) - 1

        // Extrapolate before start
        if (idx < 0) {
            val step = if (grid.size > 1) grid[1] - grid[0] else 0.5
            return (time - grid[0]) / step
        }
        // Extrapolate after end
        if (idx >= grid.size - 1) {
            val step = if (grid.size > 1) grid[grid.size - 1] - grid[grid.size - 2] else 0.5
            return (grid.size - 1) + (time - grid.last()) / step
        }

        // Interpolate inside grid
        val t1 = grid[idx]
        val t2 = grid[idx + 1]
        val fraction = (time - t1) / (t2 - t1)
        return idx + fraction
    }
}