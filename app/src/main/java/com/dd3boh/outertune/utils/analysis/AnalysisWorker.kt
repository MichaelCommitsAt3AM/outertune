package com.dd3boh.outertune.utils.analysis

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dd3boh.outertune.db.MusicDatabase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import linc.com.amplituda.Amplituda
import linc.com.amplituda.Compress
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

@HiltWorker
class AnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: MusicDatabase
) : CoroutineWorker(context, params) {

    private val amplituda = Amplituda(context)

    companion object {
        private const val TAG = "AnalysisWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "=== doWork() CALLED ===")

        val songId = inputData.getString("songId") ?: return Result.failure()

        val song = database.song(songId).first()?.song ?: run {
            Log.e(TAG, "Song not found in DB: $songId")
            return Result.failure()
        }

        val path = song.localPath ?: run {
            Log.e(TAG, "No local path for song: $songId")
            return Result.failure()
        }

        // Add leading slash if missing
        val absolutePath = if (path.startsWith("/")) path else "/$path"
        val file = File(absolutePath)

        if (!file.exists()) {
            Log.e(TAG, "Audio file does not exist: $absolutePath")
            return Result.failure()
        }

        Log.i(TAG, "Starting analysis for songId=$songId")

        return try {
            // 1. Decode Raw Audio
            val (pcmData, sampleRate) = AudioDecoder.decodeToMono(absolutePath) ?: return Result.failure()

            // 2. Calculate EXACT Duration
            // Metadata duration is often rounded. We need the exact duration to prevent visual drift.
            val exactDurationSeconds = pcmData.size.toFloat() / sampleRate.toFloat()

            // 3. Analyze BPM (Native BTrack)
            val analysisResult = AudioAnalyzer.analyzeBpm(pcmData, sampleRate) ?: return Result.failure()

            // 4. Backfill Start Beats (Mathematical)
            val filledGrid = backfillStartBeats(analysisResult.beatGrid, analysisResult.bpm)

            // 5. SNAP TO PEAKS (The "Spotify" Look)
            // We use the High-Res PCM data to align the beat to the loudest sample nearby.
            val snappedGrid = snapGridToTransients(filledGrid, pcmData, sampleRate)

            // 6. Generate Visual Waveform
            val waveformResult = amplituda.processAudio(absolutePath, Compress.withParams(Compress.AVERAGE, 100)).get()
            val amplitudes = waveformResult.amplitudesAsList()
            val maxAmplitude = amplitudes.maxOrNull()?.toFloat() ?: 1f
            val normalizedWaveform = amplitudes.map { it.toFloat() / maxAmplitude }

            // 7. Save Data
            val cacheDir = File(applicationContext.cacheDir, "analysis_data")
            cacheDir.mkdirs()

            // --- NEW: Save exact duration to metadata file ---
            // We read this in the ViewModel to prevent drift, ignoring the rounded DB value.
            File(cacheDir, "${songId}_metadata.dat").writeText(exactDurationSeconds.toString())

            File(cacheDir, "${songId}_waveform.dat").writeText(normalizedWaveform.joinToString(","))
            File(cacheDir, "${songId}_beats.dat").writeText(snappedGrid.joinToString(","))

            // 8. Update DB
            val updated = song.copy(
                waveformPath = File(cacheDir, "${songId}_waveform.dat").absolutePath,
                beatGridPath = File(cacheDir, "${songId}_beats.dat").absolutePath,
                bpm = analysisResult.bpm,
                firstBeatMs = if (snappedGrid.isNotEmpty()) (snappedGrid[0] * 1000).toLong() else 0L,

                // FIX: Cast to Int to satisfy DB type requirement.
                // (The ViewModel will look for the metadata file first to get the Float value)
                duration = exactDurationSeconds.toInt()
            )
            database.update(updated)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "=== ERROR analyzing song $songId ===", e)
            e.printStackTrace()
            Result.failure()
        }
    }

    /**
     * Aligns the mathematical BTrack timestamp to the nearest amplitude peak
     * within a +/- 50ms window.
     */
    private fun snapGridToTransients(grid: LongArray, pcm: FloatArray, sampleRate: Int): LongArray {
        val windowMs = 50
        val windowSamples = (windowMs * sampleRate / 1000)

        return grid.map { beatTimeMs ->
            // Convert ms to sample index
            val centerIndex = (beatTimeMs * sampleRate / 1000).toInt()

            // Define search bounds
            val start = (centerIndex - windowSamples).coerceAtLeast(0)
            val end = (centerIndex + windowSamples).coerceAtMost(pcm.size - 1)

            // Find max amplitude in window
            var maxIndex = centerIndex
            var maxAmp = -1f

            for (i in start..end) {
                val amp = abs(pcm[i])
                if (amp > maxAmp) {
                    maxAmp = amp
                    maxIndex = i
                }
            }

            // Convert back to ms
            (maxIndex.toLong() * 1000) / sampleRate
        }.toLongArray()
    }

    /**
     * BTrack takes time to settle, often missing the first few seconds of beats.
     * This fills in the gap from 0 to the first detected beat using pure math.
     */
    private fun backfillStartBeats(detectedGrid: LongArray, bpm: Float): LongArray {
        if (detectedGrid.isEmpty() || bpm <= 0) return detectedGrid

        val beatIntervalMs = (60_000.0 / bpm)
        val firstDetected = detectedGrid[0]

        // If the first beat is already near the start, do nothing
        if (firstDetected < beatIntervalMs) return detectedGrid

        val newBeats = ArrayList<Long>()
        var currentBeat = firstDetected.toDouble()

        // Work backwards until we hit 0
        while (currentBeat > beatIntervalMs) {
            currentBeat -= beatIntervalMs
            if (currentBeat >= 0) {
                newBeats.add(0, currentBeat.roundToLong())
            }
        }

        return (newBeats + detectedGrid.toList()).toLongArray()
    }
}