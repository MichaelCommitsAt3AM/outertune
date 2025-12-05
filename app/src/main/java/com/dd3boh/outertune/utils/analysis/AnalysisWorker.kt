package com.dd3boh.outertune.utils.analysis

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
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

    override suspend fun doWork(): Result {
        val songId = inputData.getString("songId") ?: return Result.failure()
        val path = inputData.getString("path") ?: return Result.failure()

        Log.i("AnalysisWorker", "Starting analysis for $songId")

        return try {
            val result = amplituda.processAudio(path, Compress.withParams(Compress.AVERAGE, 100)).get()

            // Save Waveform to Cache
            val waveformDir = File(applicationContext.cacheDir, "waveforms")
            waveformDir.mkdirs()
            val waveformFile = File(waveformDir, "$songId.dat")
            waveformFile.writeText(result.amplitudesAsList().joinToString(","))

            // Update Database - Call suspend functions directly
            val song = database.song(songId).first()?.song
            if (song != null) {
                val updated = song.copy(
                    waveformPath = waveformFile.absolutePath
                )
                database.update(updated)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("AnalysisWorker", "Error analyzing song", e)
            Result.failure()
        }
    }
}
