package com.dd3boh.outertune.transition.engine

import com.dd3boh.outertune.utils.analysis.BeatGridNormalizer
import kotlin.math.abs

/**
 * Builds the one beat grid a song is mixed with, in the editor and in playlists alike.
 *
 * This is exactly the pipeline the editor has always applied (normalize, then nudge the whole
 * grid onto the waveform's peaks), so transitions saved against the editor's grid line up with
 * the canonical grid without touching their rows. Pure Kotlin.
 */
object CanonicalGrid {

    /** Bump when [build] changes; stored grids with another version are rebuilt. */
    const val VERSION = 2

    /** Beats closer together than this are treated as duplicates. */
    private const val MIN_BEAT_SPACING_SEC = 0.001

    /**
     * @param rawGridSec the analysed beat times (seconds)
     * @param analysisBpm tempo found by analysis
     * @param displayBpm tempo the user sees (may be a multiple of [analysisBpm])
     * @param durationSec exact song length
     * @param waveform energy envelope spanning [durationSec]; empty skips the alignment step
     */
    fun build(
        rawGridSec: List<Double>,
        analysisBpm: Float,
        displayBpm: Float,
        durationSec: Double,
        waveform: FloatArray,
    ): List<Double> {
        val normalized = BeatGridNormalizer.resolveDjGrids(
            detectedGrid = rawGridSec.map { it.toFloat() },
            analysisBpm = analysisBpm,
            displayBpm = displayBpm,
            durationSec = durationSec.toFloat()
        ).sync.map { it.toDouble() }
        return sanitize(alignToWaveform(normalized, waveform, durationSec))
    }

    /** Sorted, finite, non-negative, strictly increasing beat times. */
    fun sanitize(grid: List<Double>): List<Double> {
        val out = ArrayList<Double>(grid.size)
        for (t in grid.filter { it.isFinite() && it >= 0.0 }.sorted()) {
            if (out.isEmpty() || t - out.last() >= MIN_BEAT_SPACING_SEC) out.add(t)
        }
        return out
    }

    /**
     * Shifts the whole grid by the offset (within ±35% of a beat) that puts the most waveform
     * energy on the beats.
     */
    fun alignToWaveform(grid: List<Double>, waveform: FloatArray, durationSec: Double): List<Double> {
        if (grid.isEmpty() || waveform.isEmpty() || durationSec <= 0.0) return grid

        val indicesPerSecond = waveform.size.toDouble() / durationSec
        val avgInterval = if (grid.size > 1) (grid.last() - grid.first()) / (grid.size - 1) else 0.5

        val searchRange = avgInterval * 0.35
        val steps = 30
        var bestOffset = 0.0
        var bestEnergy = -1.0

        for (i in -steps..steps) {
            val offset = (i.toDouble() / steps) * searchRange

            var totalEnergy = 0.0
            var count = 0
            for (beatTime in grid) {
                val t = beatTime + offset
                if (t >= 0 && t < durationSec) {
                    val index = (t * indicesPerSecond).toInt()
                    // Sum 3 samples around the point for robustness
                    if (index >= 1 && index < waveform.size - 1) {
                        totalEnergy += abs(waveform[index - 1]) + abs(waveform[index]) + abs(waveform[index + 1])
                        count++
                    }
                }
            }

            val avgEnergy = if (count > 0) totalEnergy / count else 0.0
            if (avgEnergy > bestEnergy) {
                bestEnergy = avgEnergy
                bestOffset = offset
            }
        }

        return grid.map { it + bestOffset }
    }

    /** A constant-tempo grid from BPM alone, for songs with no analysed grid. */
    fun fromBpm(bpm: Float, firstBeatSec: Double, durationSec: Double): List<Double> {
        if (bpm <= 0.1f || durationSec <= 0.0) return emptyList()
        val interval = 60.0 / bpm
        val grid = ArrayList<Double>()
        var t = firstBeatSec.coerceAtLeast(0.0)
        while (t < durationSec) {
            grid.add(t)
            t += interval
        }
        return grid
    }
}
