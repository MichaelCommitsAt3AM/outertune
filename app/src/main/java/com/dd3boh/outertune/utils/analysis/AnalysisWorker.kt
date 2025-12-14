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

        // FIX: Get SongEntity first to check for localPath
        val song = database.song(songId).first()?.song ?: run {
            Log.e(TAG, "Song not found in DB: $songId")
            return Result.failure()
        }

        // FIX: Use the song's localPath directly (works for Local and Downloaded songs)
        val path = song.localPath ?: run {
            Log.e(TAG, "No local path for song: $songId")
            return Result.failure()
        }

        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "Audio file does not exist: $path")
            return Result.failure()
        }

        // Add leading slash if missing (sometimes needed for absolute paths)
        val absolutePath = if (path.startsWith("/")) path else "/$path"

        Log.i(TAG, "Starting analysis for songId=$songId, path=$absolutePath")

        return try {
            // Step 1: Decode audio using MediaCodec
            Log.d(TAG, "Step 1: Decoding audio file...")
            val (pcmData, sampleRate) = AudioDecoder.decodeToMono(absolutePath) ?: run {
                Log.e(TAG, "Failed to decode audio file")
                return Result.failure()
            }

            // Step 2: Analyze BPM using Aubio
            Log.d(TAG, "Step 2: Running BPM analysis...")
            val analysisResult = AudioAnalyzer.analyzeBpm(pcmData, sampleRate) ?: return Result.failure()

            // --- NEW PROCESSING STEP ---
            Log.d(TAG, "Step 2.5: Refining beat grid (Snap + Backfill)...")

            val finalBeatGrid = processBeatGrid(
                analysisResult.beatGrid,
                pcmData,
                sampleRate,
                analysisResult.bpm
            )

            Log.d(TAG, "Grid size increased from ${analysisResult.beatGrid.size} to ${finalBeatGrid.size}")

            Log.d(TAG, "Step 2.5: Snapping beats to transients...")
            val alignedBeatGrid = alignBeatsToTransients(
                analysisResult.beatGrid,
                pcmData,
                sampleRate
            )

            // Step 3: Get waveform for visualization (Amplituda)
            Log.d(TAG, "Step 3: Extracting waveform...")
            val waveformResult = amplituda.processAudio(absolutePath, Compress.withParams(Compress.AVERAGE, 100)).get()
            val amplitudes = waveformResult.amplitudesAsList()

            // CRITICAL FIX: Normalize to 0.0-1.0 range
            val maxAmplitude = amplitudes.maxOrNull()?.toFloat() ?: 1f
            val normalizedWaveform = amplitudes.map { it.toFloat() / maxAmplitude }

            Log.d(TAG, "Waveform: ${amplitudes.size} points, max=$maxAmplitude, sample=${normalizedWaveform.take(5)}")

            // Save normalized waveform
            val cacheDir = File(applicationContext.cacheDir, "analysis_data")
            cacheDir.mkdirs()

            val waveformFile = File(cacheDir, "${songId}_waveform.dat")
            waveformFile.writeText(normalizedWaveform.joinToString(","))

            // Save beat grid (timestamps in seconds)
            val beatFile = File(cacheDir, "${songId}_beats.dat")
            beatFile.writeText(alignedBeatGrid.joinToString(","))
            Log.d(TAG, "Beat grid: ${analysisResult.beatGrid.size} beats, sample=${analysisResult.beatGrid.take(5)}")

            // Step 5: Update SongEntity
            val updated = song.copy(
                waveformPath = waveformFile.absolutePath,
                beatGridPath = beatFile.absolutePath,
                bpm = analysisResult.bpm,
                firstBeatMs = analysisResult.firstBeatMs
            )
            database.update(updated)
            Log.i(TAG, "=== Analysis SUCCESSFUL for $songId ===")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "=== ERROR analyzing song $songId ===", e)
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun processBeatGrid(
        roughGrid: LongArray,
        pcmData: FloatArray,
        sampleRate: Int,
        bpm: Float
    ): LongArray {
        if (roughGrid.isEmpty()) return roughGrid

        // 1. SNAP TO TRANSIENTS (Fixes misalignment)
        // We look for the "attack" (sharpest rise in energy), not just max volume.
        val snappedGrid = roughGrid.map { beatTimeMs ->
            findTransient(beatTimeMs, pcmData, sampleRate)
        }.toLongArray()

        // 2. BACK-FILL MISSING START BEATS (Fixes missing first 3-5 seconds)
        // We calculate where beats *should* be before the first detected beat.
        val firstBeat = snappedGrid.first()
        val beatIntervalMs = (60_000f / bpm).toLong()

        val newBeats = ArrayList<Long>()

        // Extrapolate backwards from the first valid beat
        var currentBeat = firstBeat - beatIntervalMs
        while (currentBeat >= 0) {
            // Optional: Attempt to snap this theoretical beat to a real transient too
            val realTransient = findTransient(currentBeat, pcmData, sampleRate)

            // Only accept the snap if it's close (within 50ms) to the grid,
            // otherwise strict time is safer for intros with no drums.
            if (kotlin.math.abs(realTransient - currentBeat) < 50) {
                newBeats.add(0, realTransient)
            } else {
                newBeats.add(0, currentBeat)
            }
            currentBeat -= beatIntervalMs
        }

        // Combine back-filled beats with original aligned beats
        return (newBeats + snappedGrid.toList()).toLongArray()
    }

    private fun findTransient(targetTimeMs: Long, pcmData: FloatArray, sampleRate: Int): Long {
        val centerSample = (targetTimeMs * sampleRate / 1000).toInt()
        val windowMs = 80
        val windowSamples = (windowMs * sampleRate / 1000).toInt()

        // FIX 1: Ensure start is at least 1, because we access pcmData[i-1] below
        val start = (centerSample - windowSamples).coerceAtLeast(1)

        // FIX 2: Ensure end does not exceed array bounds
        val end = (centerSample + windowSamples).coerceAtMost(pcmData.size - 1)

        // Safety: If the window is invalid (e.g., song is empty), return original time
        if (start >= end) return targetTimeMs

        var maxEnergyRise = -1f
        var bestIndex = centerSample

        for (i in start until end) {
            val currentAmp = kotlin.math.abs(pcmData[i])
            val prevAmp = kotlin.math.abs(pcmData[i-1]) // Safe now because i >= 1
            val rise = currentAmp - prevAmp

            if (rise > maxEnergyRise) {
                maxEnergyRise = rise
                bestIndex = i
            }
        }

        return (bestIndex.toLong() * 1000) / sampleRate
    }

    private fun alignBeatsToTransients(
        roughGrid: LongArray,
        pcmData: FloatArray,
        sampleRate: Int,
        searchWindowMs: Long = 100 // Look +/- 50ms around the detected beat
    ): LongArray {
        val windowSamples = (searchWindowMs * sampleRate / 1000).toInt()
        val halfWindow = windowSamples / 2
        val maxIndex = pcmData.size - 1

        return roughGrid.map { beatTimeMs ->
            // Convert ms to sample index
            val centerSample = (beatTimeMs * sampleRate / 1000).toInt()

            // Define search range (safely within array bounds)
            val start = (centerSample - halfWindow).coerceAtLeast(0)
            val end = (centerSample + halfWindow).coerceAtMost(maxIndex)

            // Find the index of the maximum amplitude in this window
            var maxAmp = -1f
            var maxAmpIndex = centerSample

            for (i in start..end) {
                val amp = kotlin.math.abs(pcmData[i])
                if (amp > maxAmp) {
                    maxAmp = amp
                    maxAmpIndex = i
                }
            }

            // Convert back to milliseconds
            (maxAmpIndex.toLong() * 1000) / sampleRate
        }.toLongArray()
    }
}