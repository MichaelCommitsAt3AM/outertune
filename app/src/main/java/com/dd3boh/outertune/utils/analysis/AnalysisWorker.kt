package com.dd3boh.outertune.utils.analysis

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.transition.engine.BeatGridRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import com.dd3boh.outertune.utils.DebugLog as Log

/**
 * Analyses a downloaded song for mixing: tempo and beat grid, downbeat, waveform and exact
 * duration. Writes the artifacts (see [AnalysisStorage]) and builds the canonical mixing grid.
 */
@HiltWorker
class AnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: MusicDatabase,
    private val beatGridRepository: BeatGridRepository,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AnalysisWorker"

        /** The kick lives below this; used to find where beats actually land. */
        private const val BASS_CUTOFF_HZ = 150f

        /** Waveform resolution stored for the editor. */
        private const val WAVEFORM_POINTS_PER_SECOND = 100
    }

    override suspend fun doWork(): Result {
        val songId = inputData.getString("songId") ?: return Result.failure()

        val song = database.song(songId).first()?.song ?: run {
            Log.e(TAG, "Song not found in DB: $songId")
            return Result.failure()
        }

        val path = song.localPath ?: return Result.failure()
        val absolutePath = if (path.startsWith("/")) path else "/$path"
        if (!File(absolutePath).exists()) {
            Log.e(TAG, "Audio file does not exist: $absolutePath")
            return Result.failure()
        }

        Log.i(TAG, "Starting analysis for songId=$songId")

        return try {
            val (pcm, sampleRate) = AudioDecoder.decodeToMono(applicationContext, absolutePath)
                ?: return Result.failure()
            val exactDurationSeconds = pcm.size.toDouble() / sampleRate

            // Tempo from BTrack, on the full-band signal (it needs the transients for timing).
            val analysisResult = AudioAnalyzer.analyzeBpm(pcm, sampleRate) ?: return Result.failure()

            // BTrack's tempo, drawn as a steady grid and shifted onto where the kicks land.
            val grid = generateOptimizedGrid(analysisResult.beatGrid, pcm, sampleRate)
            val intervalMs = if (grid.size > 1) (grid.last() - grid.first()).toDouble() / (grid.size - 1) else 500.0
            val correctedBpm = (60_000.0 / intervalMs).toFloat()
            Log.i(TAG) { "Corrected BPM: $correctedBpm" }

            // The bar detector does its own band split, so it gets the full-band signal.
            val barResult = BarDetector.detect(
                pcmData = pcm,
                sampleRate = sampleRate,
                beatGrid = grid.map { it / 1000f },
                timeSignature = 4
            )
            Log.i(TAG) { "Bar detection: offset=${barResult.downbeatOffset} confidence=${barResult.confidence}" }

            val waveform = generateWaveformFromPcm(pcm, sampleRate, WAVEFORM_POINTS_PER_SECOND)

            val metadataFile = AnalysisStorage.file(applicationContext, songId, AnalysisStorage.Kind.METADATA)
            val waveformFile = AnalysisStorage.file(applicationContext, songId, AnalysisStorage.Kind.WAVEFORM_BIN)
            val beatGridFile = AnalysisStorage.file(applicationContext, songId, AnalysisStorage.Kind.BEAT_GRID)

            metadataFile.writeText(exactDurationSeconds.toString())
            BinaryArtifacts.writeWaveform(waveformFile, waveform)
            beatGridFile.writeText(grid.joinToString(","))
            // Any text waveform from an older build is superseded.
            AnalysisStorage.file(applicationContext, songId, AnalysisStorage.Kind.WAVEFORM).delete()

            val updated = song.copy(
                waveformPath = waveformFile.absolutePath,
                beatGridPath = beatGridFile.absolutePath,
                bpm = correctedBpm,
                displayBpm = correctedBpm,
                firstBeatMs = grid.firstOrNull() ?: 0L,
                duration = exactDurationSeconds.roundToInt(),
                timeSignature = 4,
                downbeatOffset = barResult.downbeatOffset
            )
            database.update(updated)

            // Build the canonical mixing grid now rather than on first use.
            beatGridRepository.rebuild(updated)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing song $songId", e)
            Result.failure()
        }
    }

    /**
     * Turns BTrack's beats into a steady grid: the average interval between its first and last
     * beat (which removes per-beat jitter and drift), shifted by the median distance from each
     * grid line to the strongest bass peak near it, and extended to cover the whole song.
     *
     * @return beat times in milliseconds
     */
    private fun generateOptimizedGrid(rawGrid: LongArray, pcm: FloatArray, sampleRate: Int): LongArray {
        if (rawGrid.size < 2) return rawGrid

        val firstBeat = rawGrid.first().toDouble()
        val avgIntervalMs = (rawGrid.last() - firstBeat) / (rawGrid.size - 1)

        // Median offset between each projected beat and the bass peak within ±30% of a beat
        val searchWindowMs = (avgIntervalMs * 0.3).toInt()
        val offsets = ArrayList<Long>(rawGrid.size)
        for (i in rawGrid.indices) {
            val beatTime = (firstBeat + i * avgIntervalMs).toLong()
            val centerIdx = (beatTime * sampleRate / 1000).toInt()
            val start = (centerIdx - searchWindowMs * sampleRate / 1000).coerceAtLeast(0)
            val end = (centerIdx + searchWindowMs * sampleRate / 1000).coerceAtMost(pcm.size - 1)
            val peak = bassPeakIndex(pcm, sampleRate, start, end)
            if (peak >= 0) offsets.add(peak.toLong() * 1000 / sampleRate - beatTime)
        }
        offsets.sort()
        val globalOffset = if (offsets.isNotEmpty()) offsets[offsets.size / 2] else 0L
        Log.i(TAG) { "Grid correction: interval=${avgIntervalMs}ms offset=${globalOffset}ms" }

        val durationMs = (pcm.size.toDouble() / sampleRate * 1000).toLong()
        val beats = ArrayList<Long>()
        var current = firstBeat + globalOffset
        while (current > avgIntervalMs) current -= avgIntervalMs // back-fill the intro
        while (current < durationMs) {
            beats.add(current.roundToLong())
            current += avgIntervalMs
        }
        return beats.toLongArray()
    }

    /**
     * Index of the loudest sample in [start]..[end] after a one-pole low-pass at
     * [BASS_CUTOFF_HZ], or -1 for an empty range. The filter runs only over the window plus a
     * short warm-up (its time constant is about 1 ms), so the whole song never needs a filtered
     * copy.
     */
    private fun bassPeakIndex(pcm: FloatArray, sampleRate: Int, start: Int, end: Int): Int {
        if (start > end || pcm.isEmpty()) return -1
        val rc = 1.0f / (BASS_CUTOFF_HZ * 2 * PI.toFloat())
        val dt = 1.0f / sampleRate
        val alpha = dt / (rc + dt)

        val warmStart = (start - sampleRate / 100).coerceAtLeast(0)
        var filtered = pcm[warmStart]
        var maxAmp = -1f
        var maxIdx = -1
        for (i in warmStart..end) {
            filtered += alpha * (pcm[i] - filtered)
            if (i >= start) {
                val amp = abs(filtered)
                if (amp > maxAmp) {
                    maxAmp = amp
                    maxIdx = i
                }
            }
        }
        return maxIdx
    }

    /** RMS energy envelope at [pointsPerSecond], normalized so the loudest point is 1. */
    private fun generateWaveformFromPcm(pcm: FloatArray, sampleRate: Int, pointsPerSecond: Int): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)

        val totalPoints = (pcm.size.toDouble() / sampleRate * pointsPerSecond).toInt()
        if (totalPoints <= 0) return FloatArray(0)
        val result = FloatArray(totalPoints)
        val samplesPerPoint = pcm.size.toDouble() / totalPoints

        for (i in 0 until totalPoints) {
            val startIndex = (i * samplesPerPoint).toInt()
            val endIndex = ((i + 1) * samplesPerPoint).toInt().coerceAtMost(pcm.size)
            var sumSquares = 0.0
            for (j in startIndex until endIndex) sumSquares += pcm[j] * pcm[j]
            if (endIndex > startIndex) result[i] = sqrt(sumSquares / (endIndex - startIndex)).toFloat()
        }

        val maxRms = result.max()
        if (maxRms > 0.001f) {
            val normalizer = 1f / maxRms
            for (i in result.indices) result[i] *= normalizer
        }
        return result
    }
}
