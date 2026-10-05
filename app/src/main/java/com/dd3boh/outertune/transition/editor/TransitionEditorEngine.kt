package com.dd3boh.outertune.transition.editor

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.transition.engine.BeatGridRepository
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.ui.component.BeatGridMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.dd3boh.outertune.utils.DebugLog as Log

/**
 * Prepares what the Transition Editor draws: both songs' canonical beat grids (from
 * [BeatGridRepository], the same grids playlist playback uses) and their waveforms converted to
 * the beat domain.
 */
class TransitionEditorEngine(
    private val database: MusicDatabase,
    private val beatGrids: BeatGridRepository,
) {
    private val TAG = "TransitionEditorEngine"

    /** Null if either song is missing or hasn't been analysed. */
    suspend fun loadArtifacts(songAId: String, songBId: String): EditorArtifacts? = withContext(Dispatchers.IO) {
        try {
            val songA = database.song(songAId).firstOrNull() ?: return@withContext null
            val songB = database.song(songBId).firstOrNull() ?: return@withContext null
            val bpmA = songA.song.displayBpm ?: return@withContext null
            val bpmB = songB.song.displayBpm ?: return@withContext null

            val gridA = beatGrids.grid(songA.song) ?: return@withContext null
            val gridB = beatGrids.grid(songB.song) ?: return@withContext null
            val durA = beatGrids.durationSec(songA.song)
            val durB = beatGrids.durationSec(songB.song)

            // Track B is drawn in A's beats when the pair is interval-matched (e.g. 70 vs 140 BPM).
            val scalarB = TransitionMath.syncParameters(gridA, gridB, bpmA, bpmB).gridScalar

            val beatWfA = convertWaveformToBeatDomain(beatGrids.waveform(songA.song), gridA, durA, SAMPLES_PER_BEAT, 1.0)
            val beatWfB = convertWaveformToBeatDomain(beatGrids.waveform(songB.song), gridB, durB, SAMPLES_PER_BEAT, scalarB)

            EditorArtifacts(
                track1 = songA,
                track2 = songB,
                waveformBeatDomain1 = beatWfA,
                waveformBeatDomain2 = beatWfB,
                beatMarkers = generateBeatMarkers(beatWfA, songA.song.timeSignature, songA.song.downbeatOffset),
                rawGrid1 = gridA,
                rawGrid2 = gridB,
                durationSec1 = durA,
                durationSec2 = durB
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load artifacts", e)
            null
        }
    }

    /**
     * Converts a time-domain waveform into a beat-domain one: index 0 = beat 0,
     * [samplesPerBeat] = beat 1, etc. Each sample is the peak of its slice of the beat.
     */
    private fun convertWaveformToBeatDomain(
        waveform: FloatArray,
        grid: List<Double>,
        durationSec: Double,
        samplesPerBeat: Int,
        scalar: Double
    ): List<BeatSample> {
        if (waveform.isEmpty() || grid.size < 2 || durationSec <= 0.0) return emptyList()

        val out = ArrayList<BeatSample>((grid.size - 1) * samplesPerBeat)
        val size = waveform.size
        val indicesPerSecond = size.toDouble() / durationSec

        for (beatIndex in 0 until grid.size - 1) {
            val tGridStart = grid[beatIndex]
            val intervalDuration = grid[beatIndex + 1] - tGridStart
            if (intervalDuration <= 0.000001) continue

            for (i in 0 until samplesPerBeat) {
                val beatFractionStart = i.toDouble() / samplesPerBeat
                val timeStart = tGridStart + beatFractionStart * intervalDuration
                val timeEnd = tGridStart + ((i + 1).toDouble() / samplesPerBeat) * intervalDuration

                var maxAmp = 0f
                if (timeEnd > 0.0 && timeStart < durationSec) {
                    val idxStart = (timeStart * indicesPerSecond).toInt().coerceIn(0, size - 1)
                    val idxEnd = (timeEnd * indicesPerSecond).toInt().coerceIn(0, size)
                    for (k in idxStart until idxEnd) {
                        val amp = abs(waveform[k])
                        if (amp > maxAmp) maxAmp = amp
                    }
                }

                out.add(BeatSample(((beatIndex + beatFractionStart) * scalar).toFloat(), maxAmp))
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
            BeatGridMarker(
                beatIndex = index.toFloat(),
                isDownbeat = (index % timeSignature) == downbeatOffset,
                isGhost = false
            )
        }
    }

    private companion object {
        /** Waveform density in the editor. */
        const val SAMPLES_PER_BEAT = 64
    }
}
