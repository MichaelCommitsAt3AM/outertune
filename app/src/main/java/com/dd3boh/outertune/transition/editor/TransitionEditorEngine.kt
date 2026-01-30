package com.dd3boh.outertune.transition.editor

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.ui.component.BeatGridMarker
import com.dd3boh.outertune.utils.analysis.AudioDecoder
import com.dd3boh.outertune.utils.analysis.BeatGridNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Heavy-duty engine for processing audio data for the Transition Editor.
 *
 * Responsibilities:
 * - Loading Song metadata from DB
 * - Loading raw beat grids from disk cache
 * - Normalizing beat grids (BeatGridNormalizer)
 * - Decoding audio files to waveforms (AudioDecoder)
 * - Converting time-domain waveforms to beat-domain waveforms
 *
 * Constraints:
 * - NO ExoPlayer usage.
 * - Heavy operations must run on Dispatchers.IO.
 */
class TransitionEditorEngine(
    private val context: Context,
    private val database: MusicDatabase
) {
    private val TAG = "TransitionEditorEngine"

    suspend fun loadArtifacts(songAId: String, songBId: String): EditorArtifacts? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Loading artifacts for A=$songAId, B=$songBId")

            // 1. Fetch Metadata
            val songA = database.song(songAId).firstOrNull()
            val songB = database.song(songBId).firstOrNull()

            if (songA == null || songB == null) {
                Log.e(TAG, "Songs not found in DB")
                return@withContext null
            }

            // 2. Load Durations (Prefer cached precise duration)
            val durA = loadExactDuration(songAId) ?: songA.song.duration.toDouble()
            val durB = loadExactDuration(songBId) ?: songB.song.duration.toDouble()

            // 3. Load & Normalize Grids
            val (gridA, gridB, scalarB) = loadAndNormalizeGrids(songA, songB, durA, durB)

            // 4. Load & Process Waveforms
            // We use a fixed density for visualization (64 samples per beat)
            val samplesPerBeat = 64

            val wfA = loadWaveform(songA.song.waveformPath, songAId)
            val wfB = loadWaveform(songB.song.waveformPath, songBId)

            // 4.5. Refine Grids using Waveform Data (Snap to Peaks)
            // This ensures the visual markers verifyably line up with the audio peaks
            val alignedGridA = alignGridToWaveform(gridA, wfA, durA)
            val alignedGridB = alignGridToWaveform(gridB, wfB, durB)

            // Convert to Beat Domain (Visual alignment)
            // Note: Waveform B is scaled if we are doing Interval Matching to visually align beats
            val beatWfA = convertWaveformToBeatDomain(wfA, alignedGridA, durA, samplesPerBeat, 1.0)
            val beatWfB = convertWaveformToBeatDomain(wfB, alignedGridB, durB, samplesPerBeat, scalarB)

            // 5. Generate Markers
            val markers = generateBeatMarkers(beatWfA, songA.song.timeSignature, songA.song.downbeatOffset)

            Log.d(TAG, "Artifacts loaded successfully")

            return@withContext EditorArtifacts(
                track1 = songA,
                track2 = songB,
                waveformBeatDomain1 = beatWfA,
                waveformBeatDomain2 = beatWfB,
                beatMarkers = markers,
                rawGrid1 = alignedGridA,
                rawGrid2 = alignedGridB,
                durationSec1 = durA,
                durationSec2 = durB
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load artifacts", e)
            return@withContext null
        }
    }

    private fun loadAndNormalizeGrids(
        songA: Song,
        songB: Song,
        durA: Double,
        durB: Double
    ): Triple<List<Double>, List<Double>, Double> {
        // Load raw grids from disk
        val rawA = loadBeatGridDouble(songA.song.beatGridPath, songA.id)
        val rawB = loadBeatGridDouble(songB.song.beatGridPath, songB.id)

        // Run Normalizer (Fixes missing beats, phase drift, etc)
        val dualA = BeatGridNormalizer.resolveDjGrids(
            detectedGrid = rawA.map { it.toFloat() },
            analysisBpm = songA.song.bpm!!,
            displayBpm = songA.song.displayBpm!!,
            durationSec = durA.toFloat()
        )
        val dualB = BeatGridNormalizer.resolveDjGrids(
            detectedGrid = rawB.map { it.toFloat() },
            analysisBpm = songB.song.bpm!!,
            displayBpm = songB.song.displayBpm!!,
            durationSec = durB.toFloat()
        )

        val gridA = dualA.sync.map { it.toDouble() }
        val gridB = dualB.sync.map { it.toDouble() }

        // Determine Scalar for Track B (Interval Match decision)
        // This logic mirrors TransitionMath but is needed here for Waveform Generation
        val bpmDiff = abs(songA.song.displayBpm - songB.song.displayBpm)
        val scalarB: Double

        if (bpmDiff <= 15f) {
            scalarB = 1.0
        } else {
            val avgIntervalA = if (gridA.size > 1) (gridA.last() - gridA.first()) / (gridA.size - 1) else 0.5
            val avgIntervalB = if (gridB.size > 1) (gridB.last() - gridB.first()) / (gridB.size - 1) else 0.5
            val rawRatio = if (avgIntervalA > 0 && avgIntervalB > 0) avgIntervalB / avgIntervalA else 1.0
            val candidates = listOf(0.5, 1.0, 1.5, 2.0, 4.0)
            scalarB = candidates.minByOrNull { k -> abs(1.0 - (rawRatio / k)) } ?: 1.0
        }

        return Triple(gridA, gridB, scalarB)
    }

    /**
     * Converts a raw PCM audio waveform into a Beat-Domain waveform.
     * * This aligns audio energy to the beat grid, so index 0 = Beat 0, index 64 = Beat 1, etc.
     * Uses Double precision to prevent alignment drift over long songs.
     */
    private fun convertWaveformToBeatDomain(
        waveform: FloatArray,
        grid: List<Double>,
        durationSec: Double,
        samplesPerBeat: Int,
        scalar: Double
    ): List<BeatSample> {
        if (waveform.isEmpty() || grid.size < 2 || durationSec <= 0.0) return emptyList()

        val out = ArrayList<BeatSample>()
        val size = waveform.size
        val indicesPerSecond = size.toDouble() / durationSec

        for (beatIndex in 0 until grid.size - 1) {
            val tGridStart = grid[beatIndex]
            val tGridEnd = grid[beatIndex + 1]
            val intervalDuration = tGridEnd - tGridStart

            // Skip invalid intervals
            if (intervalDuration <= 0.000001) continue

            for (i in 0 until samplesPerBeat) {
                // Determine exact time slice for this sub-beat pixel
                val beatFractionStart = i.toDouble() / samplesPerBeat
                val beatFractionEnd = (i + 1).toDouble() / samplesPerBeat

                val timeStart = tGridStart + (beatFractionStart * intervalDuration)
                val timeEnd = tGridStart + (beatFractionEnd * intervalDuration)

                var maxAmp = 0f

                if (timeEnd > 0.0 && timeStart < durationSec) {
                    val idxStart = (timeStart * indicesPerSecond).toInt().coerceIn(0, size - 1)
                    val idxEnd = (timeEnd * indicesPerSecond).toInt().coerceIn(0, size)

                    // Find peak amplitude in this slice
                    for (k in idxStart until idxEnd) {
                        if (k < size) {
                            val amp = abs(waveform[k])
                            if (amp > maxAmp) maxAmp = amp
                        }
                    }
                }

                // Map to visual beat index (apply scalar for B)
                val originalBeatPos = beatIndex.toDouble() + beatFractionStart
                val finalBeatIndex = originalBeatPos * scalar

                out.add(BeatSample(finalBeatIndex.toFloat(), maxAmp))
            }
        }
        return out
    }

    private fun generateBeatMarkers(
        samples: List<BeatSample>,
        timeSignature: Int,
        downbeatOffset: Int
    ): List<BeatGridMarker> {
        if (samples.isEmpty()) return emptyList()

        val maxBeat = samples.last().beatIndex
        return (0..maxBeat.toInt()).map { index ->
            val isDownbeat = (index % timeSignature) == downbeatOffset
            BeatGridMarker(
                beatIndex = index.toFloat(),
                isDownbeat = isDownbeat,
                isGhost = false
            )
        }
    }

    // --- Helpers for Disk Access ---

    private fun loadExactDuration(id: String): Double? {
        val file = File(context.cacheDir, "analysis_data/${id}_metadata.dat")
        return if (file.exists()) file.readText().toDoubleOrNull() else null
    }

    private fun loadBeatGridDouble(path: String?, id: String): List<Double> {
        val file = if (path != null) File(path) else File("${context.cacheDir}/analysis_data/${id}_beats_sync.dat")
        return file.takeIf { it.exists() }?.readText()?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.map { it / 1000.0 } ?: emptyList()
    }

    private fun loadWaveform(path: String?, id: String): FloatArray {
        // First try the passed path, then cache
        val primaryFile = path?.let { File(it) }
        val cacheFile = File("${context.cacheDir}/analysis_data/${id}_waveform.dat")

        val fileToLoad = if (primaryFile != null && primaryFile.exists()) primaryFile else cacheFile

        if (!fileToLoad.exists()) {
            // If strictly required, we could trigger AudioDecoder here, but usually it's pre-analyzed.
            // For now, return empty to avoid stalling UI.
            return FloatArray(0)
        }

        return fileToLoad.readText().split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
    }

    private fun alignGridToWaveform(
        grid: List<Double>,
        waveform: FloatArray,
        durationSec: Double
    ): List<Double> {
        if (grid.isEmpty() || waveform.isEmpty()) return grid

        val indicesPerSecond = waveform.size.toDouble() / durationSec
        // Estimate average interval
        val avgInterval = if (grid.size > 1) (grid.last() - grid.first()) / (grid.size - 1) else 0.5

        // Search window: +/- 35% of the beat interval (avoid jumping to next beat)
        val searchRange = avgInterval * 0.35
        val steps = 30 // Granularity
        var bestOffset = 0.0
        var bestEnergy = -1.0

        // Scan offsets
        for (i in -steps..steps) {
            val offset = (i.toDouble() / steps) * searchRange

            var totalEnergy = 0.0
            var count = 0

            // Check energy at grid points
            // Optimization: check a subset of beats if grid is huge, but usually <500 items, so fast.
            for (beatTime in grid) {
                val t = beatTime + offset
                if (t >= 0 && t < durationSec) {
                    val index = (t * indicesPerSecond).toInt()
                    // Sum 3 samples around the point for robustness
                    if (index >= 1 && index < waveform.size - 1) {
                        val e = abs(waveform[index-1]) + abs(waveform[index]) + abs(waveform[index+1])
                        totalEnergy += e
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

        Log.d(TAG, "Refined grid by offset: ${bestOffset * 1000} ms")

        // Apply the best offset
        return grid.map { it + bestOffset }
    }
}