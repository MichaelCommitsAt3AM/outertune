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
        /**
         * Threshold for transient detection during snapping.
         * Prevents the grid from jumping to quiet noise or atmospheric pads.
         */
        private const val SNAPPING_FLUX_THRESHOLD = 0.015f
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "=== doWork() CALLED ===")

        val songId = inputData.getString("songId") ?: return Result.failure()

        // 1. Database & File Checks
        val song = database.song(songId).first()?.song ?: run {
            Log.e(TAG, "Song not found in DB: $songId")
            return Result.failure()
        }

        val path = song.localPath ?: return Result.failure()
        val absolutePath = if (path.startsWith("/")) path else "/$path"
        val file = File(absolutePath)

        if (!file.exists()) {
            Log.e(TAG, "Audio file does not exist: $absolutePath")
            return Result.failure()
        }

        Log.i(TAG, "Starting analysis for songId=$songId")

        return try {
            // 2. Decode Raw Audio
            val (pcmData, sampleRate) = AudioDecoder.decodeToMono(absolutePath) ?: return Result.failure()
            val exactDurationSeconds = pcmData.size.toFloat() / sampleRate.toFloat()

            // 150Hz cutoff keeps the kick, removes snare/vocals/hats
            // We use this for Anchoring and Bar Detection
            val bassData = lowPassFilter(pcmData, sampleRate, 150f)

            // 3. Raw Analysis (Native BTrack)
            // Note: We still pass FULL pcmData here because BTrack needs high-freq transients for timing accuracy
            val analysisResult = AudioAnalyzer.analyzeBpm(pcmData, sampleRate) ?: return Result.failure()
            val rawBpm = analysisResult.bpm
            val rawGrid = analysisResult.beatGrid

            // =========================================================================
            // 5. ANCHORING & REGENERATION (Fix for Intro/Drift Bugs)
            // =========================================================================

            val correctedBpm = rawBpm

            // Find the "Drop" (Loudest part) to anchor our grid.
            // CRITICAL CHANGE: Pass 'bassData' here to ignore clicky/synth intros.
            val anchorBeatMs = findAnchorBeat(rawGrid, bassData, sampleRate)
            Log.i(TAG, "Anchor Beat found at: $anchorBeatMs ms")

            val beatIntervalMs = 60000f / correctedBpm

            // Project backwards from Anchor to find the theoretical start (0 timestamp or negative)
            // This ensures precise alignment at the drop, even if the intro has loose timing.
            val beatsBeforeAnchor = (anchorBeatMs / beatIntervalMs).toInt()

            // This calculation finds the offset relative to 0 that makes the grid hit the anchor perfectly.
            val calculatedFirstBeatMs = (anchorBeatMs - (beatsBeforeAnchor * beatIntervalMs)).coerceAtLeast(0f)

            // Generate the full steady grid purely from math
            val totalBeats = ((exactDurationSeconds * 1000) / beatIntervalMs).toInt() + 2
            val finalGrid = LongArray(totalBeats) { i ->
                (calculatedFirstBeatMs + i * beatIntervalMs).toLong()
            }

            // SNAP TO TRANSIENTS:
            // This aligns the mathematically perfect grid to actual local transients (kicks).
            // This fixes drift caused by "human" timing, swing, or groove in the performance.
            // We use bassData for best alignment with the rhythmic foundation.
            val snappedGrid = snapGridToTransients(finalGrid, bassData, sampleRate, correctedBpm)

            // =========================================================================
            // 6. BAR DETECTION
            // =========================================================================

            val beatGridSeconds = snappedGrid.map { it / 1000f }

            // CRITICAL CHANGE: Pass 'bassData' here.
            // Bar detection works significantly better when isolating the bass line.
            val barResult = BarDetector.detect(
                pcmData = bassData,
                sampleRate = sampleRate,
                beatGrid = beatGridSeconds,
                timeSignature = 4
            )

            Log.i(TAG, "Bar Detection: Offset=${barResult.downbeatOffset} Confidence=${barResult.confidence}")

            // =========================================================================
            // 7. SAVE DATA
            // =========================================================================

            val waveformResult = amplituda.processAudio(absolutePath, Compress.withParams(Compress.AVERAGE, 100)).get()
            val amplitudes = waveformResult.amplitudesAsList()
            val maxAmplitude = amplitudes.maxOrNull()?.toFloat() ?: 1f
            val normalizedWaveform = amplitudes.map { it.toFloat() / maxAmplitude }

            val cacheDir = File(applicationContext.cacheDir, "analysis_data")
            cacheDir.mkdirs()

            File(cacheDir, "${songId}_metadata.dat").writeText(exactDurationSeconds.toString())
            File(cacheDir, "${songId}_waveform.dat").writeText(normalizedWaveform.joinToString(","))
            File(cacheDir, "${songId}_beats_sync.dat").writeText(snappedGrid.joinToString(","))

            // 8. Update DB
            val updated = song.copy(
                waveformPath = File(cacheDir, "${songId}_waveform.dat").absolutePath,
                beatGridPath = File(cacheDir, "${songId}_beats_sync.dat").absolutePath,
                bpm = correctedBpm,
                displayBpm = correctedBpm,
                firstBeatMs = if (snappedGrid.isNotEmpty()) snappedGrid[0] else 0L,
                duration = exactDurationSeconds.toInt(),
                // New Bar Detection Fields
                timeSignature = 4,
                downbeatOffset = barResult.downbeatOffset
            )
            database.update(updated)

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "=== ERROR analyzing song $songId ===", e)
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun calculateGridScore(grid: LongArray, pcm: FloatArray, sampleRate: Int, currentBpm: Float): Float {
        if (grid.isEmpty()) return 0f

        // 1. Filter: Isolate Kick/Bass (< 150Hz)
        val bassPcm = lowPassFilter(pcm, sampleRate, 150f)

        var totalFlux = 0f
        var samplesChecked = 0

        // ADAPTIVE Window: Scale with BPM
        // At 60 BPM: ~40ms, At 180 BPM: ~13ms
        val beatIntervalMs = 60_000.0 / currentBpm
        val windowMs = (beatIntervalMs * 0.08).toInt().coerceIn(10, 50) // 8% of beat interval
        val windowSamples = (windowMs * sampleRate / 1000)

        for (beatTimeMs in grid) {
            val centerIndex = (beatTimeMs * sampleRate / 1000).toInt()

            if (centerIndex < windowSamples || centerIndex >= bassPcm.size - windowSamples) continue

            // 2. Find Max Flux (Sharpest Rise) in the window
            var maxFlux = 0f

            for (i in (centerIndex - windowSamples)..(centerIndex + windowSamples)) {
                if (i <= 0) continue
                val current = abs(bassPcm[i])
                val previous = abs(bassPcm[i-1])

                val flux = (current - previous).coerceAtLeast(0f)

                if (flux > maxFlux) maxFlux = flux
            }

            totalFlux += maxFlux
            samplesChecked++
        }

        return if (samplesChecked > 0) totalFlux / samplesChecked else 0f
    }

    /**
     * Simple Low-Pass Filter (One-pole) to isolate kicks.
     */
    private fun lowPassFilter(input: FloatArray, sampleRate: Int, cutoffFreq: Float): FloatArray {
        val output = FloatArray(input.size)
        val rc = 1.0f / (cutoffFreq * 2 * Math.PI).toFloat()
        val dt = 1.0f / sampleRate
        val alpha = dt / (rc + dt)

        var previous = input[0]
        for (i in input.indices) {
            val current = input[i]
            val filtered = previous + (alpha * (current - previous))
            output[i] = filtered
            previous = filtered
        }
        return output
    }

    /**
     * Finds the timestamp of the "Anchor Beat" - the first beat in the loudest section of the song.
     * This prevents quiet intros from skewing the grid alignment.
     */
    private fun findAnchorBeat(
        rawGrid: LongArray,
        pcmData: FloatArray,
        sampleRate: Int
    ): Long {
        if (rawGrid.isEmpty()) return 0L
        if (pcmData.isEmpty()) return rawGrid[0]

        val windowSamples = (sampleRate * 0.05).toInt() // 50ms window
        var maxEnergy = 0f
        val beatEnergies = FloatArray(rawGrid.size)

        // 1. Calculate energy for every detected beat
        for (i in rawGrid.indices) {
            val beatTimeMs = rawGrid[i]
            val centerIndex = ((beatTimeMs / 1000.0) * sampleRate).toInt()
            val start = (centerIndex - windowSamples / 2).coerceAtLeast(0)
            val end = (centerIndex + windowSamples / 2).coerceAtMost(pcmData.size)

            if (start >= end) continue

            var sumSquares = 0.0
            for (j in start until end) {
                val s = pcmData[j]
                sumSquares += s * s
            }
            val rms = kotlin.math.sqrt(sumSquares / (end - start)).toFloat()
            beatEnergies[i] = rms
            if (rms > maxEnergy) maxEnergy = rms
        }

        // 2. Find the first beat that is "Loud Enough" (e.g., > 50% of peak energy)
        // This skips the quiet intro clicks but catches the first kick of the drop.
        val threshold = maxEnergy * 0.5f
        val anchorIndex = beatEnergies.indexOfFirst { it > threshold }

        return if (anchorIndex != -1) rawGrid[anchorIndex] else rawGrid[0]
    }

    /**
     * Generates a purely mathematical grid based on a start time and BPM.
     */
    private fun generateSteadyGrid(startMs: Long, bpm: Float, durationSec: Float): LongArray {
        val intervalMs = 60_000.0 / bpm
        val durationMs = durationSec * 1000
        val beats = ArrayList<Long>()

        var current = startMs.toDouble()
        if (current < 0) current = 0.0

        while (current < durationMs) {
            beats.add(current.roundToLong())
            current += intervalMs
        }

        return beats.toLongArray()
    }

    /**
     * Aligns the timestamp to the nearest onset (sudden rise in energy)
     * within a +/- window.
     *
     * IMPORVED LOGIC: Instead of looking for the loudest sample (Max Amplitude),
     * this looks for the sharpest *rise* in energy (Max Flux).
     * This fixes issues in intros/outros where a quiet beat (hi-hat/kick) might be
     * overpowered by a loud swelling synth pad.
     */
    private fun snapGridToTransients(grid: LongArray, pcm: FloatArray, sampleRate: Int, currentBpm: Float): LongArray {
        // ADAPTIVE Window based on BPM
        val beatIntervalMs = 60_000.0 / currentBpm
        val windowMs = (beatIntervalMs * 0.12).toInt().coerceIn(15, 80) // 12% of beat interval
        val windowSamples = (windowMs * sampleRate / 1000)
        val alpha = 0.90f

        return grid.map { beatTimeMs ->
            val centerIndex = (beatTimeMs * sampleRate / 1000).toInt()

            // Skip beats outside PCM range
            if (centerIndex <= 0 || centerIndex >= pcm.size - 1) {
                return@map beatTimeMs
            }

            val start = (centerIndex - windowSamples).coerceIn(1, pcm.size - 2)
            val end = (centerIndex + windowSamples).coerceAtMost(pcm.size - 1)

            var maxFluxIndex = centerIndex
            var maxFlux = -1f
            var previousEnvelope = abs(pcm[start - 1])

            for (i in start..end) {
                val currentAbs = abs(pcm[i])
                val currentEnvelope =
                    if (currentAbs > previousEnvelope) currentAbs
                    else previousEnvelope * alpha + currentAbs * (1 - alpha)

                val flux = (currentEnvelope - previousEnvelope).coerceAtLeast(0f)

                if (flux > maxFlux) {
                    maxFlux = flux
                    maxFluxIndex = i
                }
                previousEnvelope = currentEnvelope
            }

            if (maxFlux >= SNAPPING_FLUX_THRESHOLD) {
                (maxFluxIndex.toLong() * 1000) / sampleRate
            } else {
                beatTimeMs
            }
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

        if (firstDetected < beatIntervalMs) return detectedGrid

        val newBeats = ArrayList<Long>()
        var currentBeat = firstDetected.toDouble()

        while (currentBeat > beatIntervalMs) {
            currentBeat -= beatIntervalMs
            if (currentBeat >= 0) {
                newBeats.add(0, currentBeat.roundToLong())
            }
        }

        return (newBeats + detectedGrid.toList()).toLongArray()
    }
}

// Add helper in companion object or as extension
private fun List<Float>.toMillisLongArray() = map { (it * 1000).toLong() }.toLongArray()
