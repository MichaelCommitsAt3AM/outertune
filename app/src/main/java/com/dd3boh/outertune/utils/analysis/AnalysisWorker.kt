package com.dd3boh.outertune.utils.analysis

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AnalysisStatus
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

        val songId = inputData.getString("songId")

        // Check songId is not null first
        if (songId == null) {
            Log.e(TAG, "songId is NULL - returning failure")
            return Result.failure()
        }

        // Get download info from database
        val downloadDao = database.downloadDao()
        val download = downloadDao.getDownload(songId) ?: return Result.failure()

        val path = download.localPath

        Log.d(TAG, "Input data - songId: $songId, path: $path")

        if (path == null) {
            Log.e(TAG, "path is NULL - returning failure")
            return Result.failure()
        }

        val file = File(path)
        if (!file.exists()) {
            downloadDao.updateAnalysisStatus(songId, AnalysisStatus.FAILED)
            return Result.failure()
        }

        // Update to in progress
        downloadDao.updateAnalysisStatus(songId, AnalysisStatus.IN_PROGRESS)

        // Add leading slash if missing
        val absolutePath = if (path.startsWith("/")) path else "/$path"

        Log.i(TAG, "Starting analysis for songId=$songId, path=$absolutePath")

        return try {
            // Step 1: Decode audio using MediaCodec
            Log.d(TAG, "Step 1: Decoding audio file...")
            val (pcmData, sampleRate) = AudioDecoder.decodeToMono(absolutePath) ?: run {
                Log.e(TAG, "Failed to decode audio file")
                downloadDao.updateAnalysisStatus(songId, AnalysisStatus.FAILED)
                return Result.failure()
            }
            Log.d(TAG, "Step 1: Decoded ${pcmData.size} samples at $sampleRate Hz")

            // Step 2: Analyze BPM using Aubio
            Log.d(TAG, "Step 2: Running BPM analysis...")
            val analysisResult = AudioAnalyzer.analyzeBpm(pcmData, sampleRate)
            Log.d(TAG, "Step 2: Analysis complete - BPM=${analysisResult?.bpm}, beats=${analysisResult?.beatGrid?.size}")

            if (analysisResult == null) {
                Log.e(TAG, "Analysis returned null")
                downloadDao.updateAnalysisStatus(songId, AnalysisStatus.FAILED)
                return Result.failure()
            }

            // Step 3: Get waveform for visualization (still use Amplituda)
            Log.d(TAG, "Step 3: Extracting waveform...")
            val waveformData = amplituda.processAudio(absolutePath, Compress.withParams(Compress.AVERAGE, 100)).get()
            val amplitudes = waveformData.amplitudesAsList()

            // Step 4: Save analysis artifacts
            Log.d(TAG, "Step 4: Saving analysis artifacts...")
            val cacheDir = File(applicationContext.cacheDir, "analysis_data")
            cacheDir.mkdirs()
            Log.d(TAG, "Step 4: Cache directory: ${cacheDir.absolutePath}, exists=${cacheDir.exists()}")

            // Save waveform
            val waveformFile = File(cacheDir, "${songId}_waveform.dat")
            waveformFile.writeText(amplitudes.joinToString(","))
            Log.d(TAG, "Step 4: Waveform saved to ${waveformFile.absolutePath}, size=${waveformFile.length()} bytes")

            // Save beat grid
            val beatFile = File(cacheDir, "${songId}_beats.dat")
            beatFile.writeText(analysisResult.beatGrid.joinToString(","))
            Log.d(TAG, "Step 4: Beat grid saved to ${beatFile.absolutePath}, size=${beatFile.length()} bytes")

            // Step 5: Update database
            Log.d(TAG, "Step 5: Updating database...")
            val song = database.song(songId).first()?.song
            Log.d(TAG, "Step 5: Retrieved song from DB: ${song?.let { "id=${it.id}" } ?: "NULL"}")

            if (song != null) {
                val updated = song.copy(
                    waveformPath = waveformFile.absolutePath,
                    beatGridPath = beatFile.absolutePath,
                    bpm = analysisResult.bpm,
                    firstBeatMs = analysisResult.firstBeatMs,
                    key = null
                )
                Log.d(TAG, "Step 5: Updating song with BPM=${updated.bpm}, waveformPath=${updated.waveformPath}")
                database.update(updated)
                Log.i(TAG, "Step 5: Database update complete")

                // Update analysis status to COMPLETED
                downloadDao.updateAnalysisStatus(songId, AnalysisStatus.COMPLETED)
                Log.i(TAG, "=== Analysis SUCCESSFUL for $songId, BPM=${analysisResult.bpm} ===")
            } else {
                Log.w(TAG, "Step 5: Song not found in database, skipping update")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "=== ERROR analyzing song $songId ===", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${e.message}")
            e.printStackTrace()

            // Update status to FAILED on error
            downloadDao.updateAnalysisStatus(songId, AnalysisStatus.FAILED)

            Result.failure()
        }
    }
}
