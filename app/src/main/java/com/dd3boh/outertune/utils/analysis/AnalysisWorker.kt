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

            // 3. Calculate EXACT Duration
            val exactDurationSeconds = pcmData.size.toFloat() / sampleRate.toFloat()

            // 4. Raw Analysis (Native BTrack)
            val analysisResult = AudioAnalyzer.analyzeBpm(pcmData, sampleRate) ?: return Result.failure()
            val rawBpm = analysisResult.bpm
            val rawGrid = analysisResult.beatGrid

            // =========================================================================
            // 5. FLUX-BASED CANDIDATE SELECTION (Pure Meritocracy)
            // =========================================================================

            val firstBeatMs = if (rawGrid.isNotEmpty()) rawGrid[0] else 0L

            // A. Calculate Baseline Score
            // We evaluate how well the Raw BPM aligns with the Flux (Kick attacks)
            val originalGrid = generateSteadyGrid(firstBeatMs, rawBpm, exactDurationSeconds)
            val originalScore = calculateGridScore(originalGrid, pcmData, sampleRate)

            Log.d(TAG, "Baseline (Original) BPM: $rawBpm | Flux Score: $originalScore")

            // B. Define Candidates
            val candidates = mutableMapOf<String, Float>()

            // Standard Octave Checks
            if (rawBpm < 95) candidates["Double"] = rawBpm * 2
            if (rawBpm > 175) candidates["Half"] = rawBpm / 2

            // The Polyrhythm Candidate (115 -> 152)
            // We still GENERATE this candidate to test it, but we won't give it special bias.
            if (rawBpm in 110f..120f) {
                candidates["Polyrhythm_Check"] = rawBpm * (4f / 3f)
            }

            var bestBpm = rawBpm
            var currentBestScore = originalScore

            // C. Test Candidates
            for ((type, candidateBpm) in candidates) {
                val testGrid = generateSteadyGrid(firstBeatMs, candidateBpm, exactDurationSeconds)
                val candidateScore = calculateGridScore(testGrid, pcmData, sampleRate)

                // DECISION LOGIC:
                // All candidates must be significantly better (> 5%) than the original to justify
                // overriding the native analysis. No special treatment for specific genres.
                val threshold = originalScore * 1.02f

                if (candidateScore > threshold) {
                    // It qualifies! Now does it beat the *current* best?
                    // (e.g., if Double and Polyrhythm both qualify, pick the highest score)
                    if (bestBpm == rawBpm || candidateScore > currentBestScore) {
                        bestBpm = candidateBpm
                        currentBestScore = candidateScore
                        Log.i(TAG, "Candidate $type ($candidateBpm) took the lead! Score: $candidateScore")
                    }
                } else {
                    Log.d(TAG, "Candidate $type ($candidateBpm) rejected. Score $candidateScore vs Thresh $threshold")
                }
            }

            Log.i(TAG, "Final Decision: $bestBpm (Raw was: $rawBpm)")

            val correctedBpm = bestBpm
            val bpmChanged = abs(correctedBpm - rawBpm) > 1.0f

            // =========================================================================
            // 6. REGENERATE & SNAP
            // =========================================================================

            val processingGrid: LongArray = if (bpmChanged) {
                // BPM changed -> Regenerate steady grid from math
                generateSteadyGrid(firstBeatMs, correctedBpm, exactDurationSeconds)
            } else {
                // BPM same -> Keep raw grid
                rawGrid
            }

            // Snap to exact transients (using Raw PCM for precision)
            val snappedGrid = snapGridToTransients(processingGrid, pcmData, sampleRate)

            // Backfill start
            val finalGrid = backfillStartBeats(snappedGrid, correctedBpm)

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
            File(cacheDir, "${songId}_beats_sync.dat").writeText(finalGrid.joinToString(","))

            // 8. Update DB
            val updated = song.copy(
                waveformPath = File(cacheDir, "${songId}_waveform.dat").absolutePath,
                beatGridPath = File(cacheDir, "${songId}_beats_sync.dat").absolutePath,
                bpm = correctedBpm,
                displayBpm = correctedBpm,
                firstBeatMs = if (finalGrid.isNotEmpty()) finalGrid[0] else 0L,
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

    private fun calculateGridScore(grid: LongArray, pcm: FloatArray, sampleRate: Int): Float {
        if (grid.isEmpty()) return 0f

        // 1. Filter: Isolate Kick/Bass (< 150Hz)
        val bassPcm = lowPassFilter(pcm, sampleRate, 150f)

        var totalFlux = 0f
        var samplesChecked = 0

        // Window: 20ms (Tight window to find the attack)
        val windowMs = 20
        val windowSamples = (windowMs * sampleRate / 1000)

        for (beatTimeMs in grid) {
            val centerIndex = (beatTimeMs * sampleRate / 1000).toInt()

            if (centerIndex < windowSamples || centerIndex >= bassPcm.size - windowSamples) continue

            // 2. Find Max Flux (Sharpest Rise) in the window
            var maxFlux = 0f

            for (i in (centerIndex - windowSamples)..(centerIndex + windowSamples)) {
                val current = abs(bassPcm[i])
                val previous = abs(bassPcm[i-1]) // Simple 1-sample derivative

                // Only count positive increases (Attacks)
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
        val dt = 1.0f / sampleRate
        val rc = 1.0f / (2.0f * Math.PI.toFloat() * cutoffFreq)
        val alpha = dt / (rc + dt)

        var previous = input[0]
        for (i in input.indices) {
            val current = input[i]
            val filtered = previous + alpha * (current - previous)
            output[i] = filtered
            previous = filtered
        }
        return output
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
    private fun snapGridToTransients(grid: LongArray, pcm: FloatArray, sampleRate: Int): LongArray {
        // This function is critical: it takes the "Steady Grid" and wiggles every beat
        // to land on the loudest nearby sample.

        val windowMs = 50
        val windowSamples = (windowMs * sampleRate / 1000)
        val alpha = 0.90f

        return grid.map { beatTimeMs ->
            val centerIndex = (beatTimeMs * sampleRate / 1000).toInt()
            val start = (centerIndex - windowSamples).coerceAtLeast(1)
            val end = (centerIndex + windowSamples).coerceAtMost(pcm.size - 1)

            var maxFluxIndex = centerIndex
            var maxFlux = -1f
            var previousEnvelope = abs(pcm[start - 1])

            for (i in start..end) {
                val currentAbs = abs(pcm[i])
                val currentEnvelope = if (currentAbs > previousEnvelope) currentAbs else previousEnvelope * alpha + currentAbs * (1 - alpha)
                val flux = (currentEnvelope - previousEnvelope).coerceAtLeast(0f)

                if (flux > maxFlux) {
                    maxFlux = flux
                    maxFluxIndex = i
                }
                previousEnvelope = currentEnvelope
            }
            (maxFluxIndex.toLong() * 1000) / sampleRate
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

