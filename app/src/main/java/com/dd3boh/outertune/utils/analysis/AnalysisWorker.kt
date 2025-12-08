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
            val analysisResult = AudioAnalyzer.analyzeBpm(pcmData, sampleRate) ?: run {
                Log.e(TAG, "Analysis returned null")
                return Result.failure()
            }

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
            beatFile.writeText(analysisResult.beatGrid.joinToString(","))
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
}