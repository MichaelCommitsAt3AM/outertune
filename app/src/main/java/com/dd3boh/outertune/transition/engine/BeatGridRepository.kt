package com.dd3boh.outertune.transition.engine

import android.content.Context
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.utils.analysis.AnalysisStorage
import com.dd3boh.outertune.utils.analysis.BinaryArtifacts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import com.dd3boh.outertune.utils.DebugLog as Log

/**
 * The only way the app reads beat grids and waveforms. The editor and playlist playback both get
 * their grid here, so a transition is always played on the grid it was edited on.
 *
 * The canonical grid ([CanonicalGrid.build]) is stored next to the raw analysis output and
 * rebuilt whenever it is missing, from an older version, or built for a different BPM.
 */
@Singleton
class BeatGridRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val gridCache = object : LinkedHashMap<String, List<Double>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Double>>) = size > CACHE_SIZE
    }

    /** The canonical grid (seconds) for [songId], or null if the song has no BPM. */
    suspend fun grid(songId: String): List<Double>? {
        val song = database.song(songId).firstOrNull()?.song ?: return null
        return grid(song)
    }

    suspend fun grid(song: SongEntity): List<Double>? = withContext(Dispatchers.IO) {
        val displayBpm = song.displayBpm ?: return@withContext null
        val cacheKey = "${song.id}@$displayBpm"
        synchronized(gridCache) { gridCache[cacheKey] }?.let { return@withContext it }

        val grid = loadOrBuild(song, displayBpm)
        if (grid != null) synchronized(gridCache) { gridCache[cacheKey] = grid }
        grid
    }

    /** Rebuilds and stores the canonical grid now (e.g. right after analysis). */
    suspend fun rebuild(song: SongEntity): List<Double>? = withContext(Dispatchers.IO) {
        synchronized(gridCache) { gridCache.keys.removeAll { it.startsWith("${song.id}@") } }
        AnalysisStorage.file(context, song.id, AnalysisStorage.Kind.CANONICAL_GRID).delete()
        grid(song)
    }

    /** The waveform energy envelope, or empty if the song wasn't analysed. */
    suspend fun waveform(song: SongEntity): FloatArray = withContext(Dispatchers.IO) { loadWaveform(song) }

    /** Exact analysed length in seconds, falling back to the database's whole seconds. */
    suspend fun durationSec(song: SongEntity): Double = withContext(Dispatchers.IO) { loadDuration(song) }

    private fun loadOrBuild(song: SongEntity, displayBpm: Float): List<Double>? {
        val analysisBpm = song.bpm ?: displayBpm
        val stored = AnalysisStorage.file(context, song.id, AnalysisStorage.Kind.CANONICAL_GRID)

        BinaryArtifacts.readGrid(stored)?.let { grid ->
            if (grid.version == CanonicalGrid.VERSION &&
                abs(grid.displayBpm - displayBpm) < BPM_EPSILON &&
                abs(grid.analysisBpm - analysisBpm) < BPM_EPSILON &&
                grid.beatsSec.isNotEmpty()
            ) {
                return grid.beatsSec.toList()
            }
        }

        val raw = loadRawGrid(song)
        val durationSec = loadDuration(song)
        if (raw.isEmpty()) {
            // Never analysed: a plain constant-tempo grid from the BPM. Not stored, since it would
            // go stale as soon as the song is analysed.
            return CanonicalGrid.fromBpm(displayBpm, (song.firstBeatMs ?: 0L) / 1000.0, durationSec)
                .ifEmpty { null }
        }

        val built = CanonicalGrid.build(raw, analysisBpm, displayBpm, durationSec, loadWaveform(song))
        if (built.isEmpty()) return null
        try {
            BinaryArtifacts.writeGrid(
                stored,
                BinaryArtifacts.StoredGrid(CanonicalGrid.VERSION, analysisBpm, displayBpm, built.toDoubleArray())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not store canonical grid for ${song.id}", e)
        }
        return built
    }

    private fun loadRawGrid(song: SongEntity): List<Double> {
        val file = AnalysisStorage.resolve(context, song.beatGridPath, song.id, AnalysisStorage.Kind.BEAT_GRID)
            ?: return emptyList()
        return try {
            file.readText().split(',', '\n').mapNotNull { it.trim().toDoubleOrNull() }.map { it / 1000.0 }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read beat grid for ${song.id}", e)
            emptyList()
        }
    }

    private fun loadWaveform(song: SongEntity): FloatArray {
        val binary = AnalysisStorage.file(context, song.id, AnalysisStorage.Kind.WAVEFORM_BIN)
        BinaryArtifacts.readWaveform(binary)?.let { return it }

        // Legacy text waveform: read it once and keep a binary copy.
        val text = AnalysisStorage.resolve(context, song.waveformPath, song.id, AnalysisStorage.Kind.WAVEFORM)
            ?: return FloatArray(0)
        return try {
            text.readText().split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray().also {
                if (it.isNotEmpty()) BinaryArtifacts.writeWaveform(binary, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read waveform for ${song.id}", e)
            FloatArray(0)
        }
    }

    private fun loadDuration(song: SongEntity): Double {
        val exact = AnalysisStorage.resolve(context, null, song.id, AnalysisStorage.Kind.METADATA)
            ?.let { runCatching { it.readText().trim().toDoubleOrNull() }.getOrNull() }
        return exact ?: song.duration.toDouble()
    }

    private companion object {
        const val TAG = "BeatGridRepository"
        const val CACHE_SIZE = 8
        const val BPM_EPSILON = 0.01f
    }
}
