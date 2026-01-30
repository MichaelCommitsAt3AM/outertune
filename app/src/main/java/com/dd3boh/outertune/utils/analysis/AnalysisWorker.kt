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
            val (pcmData, sampleRate) = AudioDecoder.decodeToMono(applicationContext, absolutePath) ?: return Result.failure()
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
            // 5. ANCHORING & GENERATION (Drift Fix)
            // =========================================================================

            // We use the raw analysis to determine the tempo, but we use our own
            // math to draw the lines.
            val finalGrid = generateOptimizedGrid(
                rawGrid = rawGrid,
                pcm = bassData, // Use filtered bass for best alignment
                sampleRate = sampleRate
            )

            // Calculate the corrected BPM from our precise interval
            val intervalMs = if (finalGrid.size > 1) (finalGrid.last() - finalGrid.first()).toDouble() / (finalGrid.size - 1) else 500.0
            val correctedBpm = (60_000.0 / intervalMs).toFloat()

            Log.i(TAG, "Final Corrected BPM: $correctedBpm")

            // Use this strictly
            val snappedGrid = finalGrid

            snappedGrid.forEachIndexed { index, beatTimeMs ->
                if (index % 32 == 0) {
                    Log.d(TAG, "BeatMarker[$index]: ${beatTimeMs} ms")
                }
            }


            // =========================================================================
            // 6. BAR DETECTION
            // =========================================================================

            val beatGridSeconds = snappedGrid.map { it / 1000f }

            // CRITICAL UPDATE: Pass FULL 'pcmData' here.
            // The new BarDetector performs its own internal 3-band split (Low/Mid/High).
            // Passing 'bassData' would strip the Mid/High bands, breaking the new algorithm.
            val barResult = BarDetector.detect(
                pcmData = pcmData,
                sampleRate = sampleRate,
                beatGrid = beatGridSeconds,
                timeSignature = 4
            )

            Log.i(TAG, "Bar Detection: Offset=${barResult.downbeatOffset} Confidence=${barResult.confidence}")

            // =========================================================================
            // 7. SAVE DATA
            // =========================================================================

//            val waveformResult = amplituda.processAudio(absolutePath, Compress.withParams(Compress.AVERAGE, 100)).get()
//            val amplitudes = waveformResult.amplitudesAsList()
//            val maxAmplitude = amplitudes.maxOrNull()?.toFloat() ?: 1f
//            val normalizedWaveform = amplitudes.map { it.toFloat() / maxAmplitude }

            // Use the PCM data you already decoded at the start of doWork
            val normalizedWaveform = generateWaveformFromPcm(pcmData, sampleRate, targetPointsPerSecond = 100)

            val cacheDir = File(applicationContext.cacheDir, "analysis_data")
            cacheDir.mkdirs()

            // Save Exact Duration as String representation of Double to avoid Float precision loss
            val preciseDurationString = exactDurationSeconds.toString()

            File(cacheDir, "${songId}_metadata.dat").writeText(preciseDurationString)
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
     * Generates a Dynamic Grid that "chases" the audio.
     * Instead of a rigid ruler, it projects the next beat based on the
     * PREVIOUS beat's actual snapped location. This kills cumulative drift.
     */
    private fun generateDynamicGrid(
        anchorMs: Long,
        bpm: Float,
        durationSec: Float,
        pcm: FloatArray,
        sampleRate: Int
    ): LongArray {
        val intervalMs = 60_000.0 / bpm
        val durationMs = (durationSec * 1000).toLong()
        val beats = ArrayList<Long>()

        // 1. Backfill from Anchor to Start (Reverse math is safe for intros)
        var current = anchorMs.toDouble()
        while (current > 0) {
            beats.add(0, current.roundToLong())
            current -= intervalMs
        }

        // 2. Dynamic Forward Generation (The Drift Fix)
        // Start projecting from the Anchor
        var previousBeatTime = anchorMs.toDouble()

        // We stop when the NEXT predicted beat is outside the song
        while (previousBeatTime + intervalMs < durationMs) {

            // A. Predict where the next beat SHOULD be relative to the LAST one
            val predictedNextBeat = previousBeatTime + intervalMs

            // B. Snap this single beat to the nearest transient in the audio
            val snappedBeat = snapSingleBeat(
                predictedTimeMs = predictedNextBeat,
                pcm = pcm,
                sampleRate = sampleRate,
                searchWindowMs = (intervalMs * 0.30) // Search 30% of beat width (wide window)
            )

            beats.add(snappedBeat)

            // C. CRITICAL: Use the SNAPPED time as the baseline for the next beat.
            // This prevents error from accumulating.
            // Safety Check: If snap jumped too far (bad detection), stay on rigid grid.
            if (abs(snappedBeat - predictedNextBeat) < (intervalMs * 0.4)) {
                previousBeatTime = snappedBeat.toDouble()
            } else {
                previousBeatTime = predictedNextBeat
            }
        }

        return beats.toLongArray()
    }

    /**
     * Helper to find the sharpest transient near a specific timestamp.
     */
    private fun snapSingleBeat(
        predictedTimeMs: Double,
        pcm: FloatArray,
        sampleRate: Int,
        searchWindowMs: Double
    ): Long {
        val centerIndex = (predictedTimeMs * sampleRate / 1000).toInt()
        val windowSamples = (searchWindowMs * sampleRate / 1000).toInt()

        val start = (centerIndex - windowSamples).coerceIn(1, pcm.size - 2)
        val end = (centerIndex + windowSamples).coerceAtMost(pcm.size - 2)

        var maxFlux = -1f
        var bestIndex = centerIndex
        val alpha = 0.90f
        var previousEnvelope = abs(pcm[start - 1])

        for (i in start..end) {
            val currentAbs = abs(pcm[i])
            // Simple envelope follower
            val currentEnvelope = if (currentAbs > previousEnvelope) currentAbs
            else previousEnvelope * alpha + currentAbs * (1 - alpha)

            // Flux = Rise in energy
            val flux = (currentEnvelope - previousEnvelope).coerceAtLeast(0f)

            if (flux > maxFlux) {
                maxFlux = flux
                bestIndex = i
            }
            previousEnvelope = currentEnvelope
        }

        // Only accept the snap if there's actual energy (avoid snapping to silence)
        return if (maxFlux > SNAPPING_FLUX_THRESHOLD) {
            (bestIndex.toLong() * 1000) / sampleRate
        } else {
            predictedTimeMs.toLong()
        }
    }

    private fun generateOptimizedGrid(
        rawGrid: LongArray,
        pcm: FloatArray,
        sampleRate: Int
    ): LongArray {
        if (rawGrid.size < 2) return rawGrid

        // A. CALCULATE PRECISE INTERVAL (Drift Fix)
        // Instead of trusting the BPM, we measure the physical distance between
        // the first and last reliable beats.
        val firstBeat = rawGrid.first().toDouble()
        val lastBeat = rawGrid.last().toDouble()
        val totalBeats = rawGrid.size - 1

        // This is the mathematically perfect interval for this file
        val avgIntervalMs = (lastBeat - firstBeat) / totalBeats

        // B. GENERATE STEADY GRID
        // We initially project from the first detected beat
        val projectedGrid = LongArray(rawGrid.size) { i ->
            (firstBeat + (i * avgIntervalMs)).toLong()
        }

        // C. PHASE CORRECTION (Latency Fix)
        // The grid might be perfect tempo, but shifted 50ms early/late.
        // We check the error for every beat and find the "Median Offset".
        val offsets = ArrayList<Long>()

        // Check window: +/- 30% of a beat
        val searchWindow = (avgIntervalMs * 0.3).toInt()

        for (beatTime in projectedGrid) {
            val centerIdx = (beatTime * sampleRate / 1000).toInt()
            val start = (centerIdx - searchWindow * sampleRate / 1000).coerceAtLeast(0)
            val end = (centerIdx + searchWindow * sampleRate / 1000).coerceAtMost(pcm.size - 1)

            var maxAmp = -1f
            var maxIdx = -1

            // Find the actual peak (Kick drum) near this grid line
            for (j in start..end) {
                val amp = kotlin.math.abs(pcm[j])
                if (amp > maxAmp) {
                    maxAmp = amp
                    maxIdx = j
                }
            }

            if (maxIdx != -1) {
                val actualTime = (maxIdx.toLong() * 1000) / sampleRate
                offsets.add(actualTime - beatTime)
            }
        }

        // Calculate Median Offset (Median is better than Average to ignore outliers/errors)
        offsets.sort()
        val globalOffset = if (offsets.isNotEmpty()) offsets[offsets.size / 2] else 0L

        Log.i(TAG, "Grid Correction: AvgInterval=$avgIntervalMs, GlobalOffset=${globalOffset}ms")

        // D. APPLY FINAL SHIFT AND BACKFILL
        // Re-generate the grid with the global offset applied
        val durationMs = (pcm.size.toDouble() / sampleRate * 1000).toLong()
        val finalBeats = ArrayList<Long>()

        // Start from the corrected first beat
        var current = (firstBeat + globalOffset)

        // Backfill to 0 (Intro)
        while (current > avgIntervalMs) {
            current -= avgIntervalMs
        }

        // Fill forward
        while (current < durationMs) {
            finalBeats.add(current.roundToLong())
            current += avgIntervalMs
        }

        return finalBeats.toLongArray()
    }


    private fun generateWaveformFromPcm(pcm: FloatArray, sampleRate: Int, targetPointsPerSecond: Int = 100): List<Float> {
        if (pcm.isEmpty()) return emptyList()

        // 1. Calculate precise point count to maintain sync
        val durationSeconds = pcm.size.toDouble() / sampleRate.toDouble()
        val totalPoints = (durationSeconds * targetPointsPerSecond).toInt()

        val result = FloatArray(totalPoints)
        val samplesPerPoint = pcm.size.toDouble() / totalPoints

        // 2. Iterate through points
        for (i in 0 until totalPoints) {
            val startIndex = (i * samplesPerPoint).toInt()
            val endIndex = ((i + 1) * samplesPerPoint).toInt().coerceAtMost(pcm.size)

            var sumSquares = 0.0
            var count = 0

            // 3. Calculate RMS (Root Mean Square) for this window
            // This visualizes "Energy" rather than "Peak Volume"
            for (j in startIndex until endIndex) {
                val s = pcm[j]
                sumSquares += (s * s)
                count++
            }

            if (count > 0) {
                val rms = kotlin.math.sqrt(sumSquares / count).toFloat()
                result[i] = rms
            }
        }

        // 4. Normalize based on the LOUDEST RMS chunk found.
        // This ensures the waveform touches the top of the UI but retains dynamic dips.
        val maxRms = result.maxOrNull() ?: 1f

        // Avoid division by zero
        val normalizer = if (maxRms > 0.001f) 1f / maxRms else 1f

        return result.map { it * normalizer }
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
                val snappedTime = (maxFluxIndex.toLong() * 1000) / sampleRate

                // --- ADD THIS LOG BLOCK ---
                val diff = snappedTime - beatTimeMs
                // Log every 4th beat to reduce spam, or if drift is significant
                if (diff > 20 || diff < -20 || (beatTimeMs / 1000).toInt() % 4 == 0) {
                    Log.d(TAG, "DriftCheck: MathTime=$beatTimeMs, SnappedTime=$snappedTime, Diff=${diff}ms")
                }

                snappedTime
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
