package com.dd3boh.outertune.utils.analysis

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-disk location for per-song analysis artifacts (beat grid, waveform, precise duration).
 *
 * These files were originally written to [Context.getCacheDir], which Android is free to evict
 * under storage pressure — silently breaking every saved transition that depends on the grid.
 * They now live under [Context.getFilesDir]; [migrateLegacyCache] moves any pre-existing files
 * and MIGRATION_26_27 rewrites the stored DB paths.
 */
object AnalysisStorage {
    private const val TAG = "AnalysisStorage"
    private const val DIR = "analysis_data"

    enum class Kind(internal val suffix: String) {
        BEAT_GRID("_beats_sync.dat"),
        WAVEFORM("_waveform.dat"),
        METADATA("_metadata.dat"),
    }

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun legacyDir(context: Context): File = File(context.cacheDir, DIR)

    fun file(context: Context, songId: String, kind: Kind): File =
        File(dir(context), songId + kind.suffix)

    /**
     * Resolve the on-disk file for an artifact, tolerating a stale [storedPath] left by an older
     * build (cacheDir location, or a path that no longer exists). If the file is only found in the
     * legacy cache dir it is promoted to filesDir. Returns null if the artifact genuinely isn't cached.
     */
    fun resolve(context: Context, storedPath: String?, songId: String, kind: Kind): File? {
        storedPath?.let(::File)?.takeIf { it.isFile }?.let { return it }

        val current = file(context, songId, kind)
        if (current.isFile) return current

        val legacy = File(legacyDir(context), current.name)
        if (legacy.isFile) {
            return try {
                legacy.copyTo(current, overwrite = true).also { legacy.delete() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to promote ${legacy.name} from cache", e)
                legacy
            }
        }
        return null
    }

    /**
     * One-time bulk move of the legacy cacheDir artifacts into filesDir. Cheap and idempotent —
     * safe to call on every launch. Does not touch the database (MIGRATION_26_27 handles paths).
     */
    fun migrateLegacyCache(context: Context) {
        val legacy = legacyDir(context)
        if (!legacy.isDirectory) return
        val target = dir(context)
        var moved = 0
        legacy.listFiles()?.forEach { src ->
            val dest = File(target, src.name)
            try {
                if (!dest.exists()) {
                    src.copyTo(dest)
                    moved++
                }
                src.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to migrate ${src.name}", e)
            }
        }
        legacy.delete() // only succeeds if now empty
        if (moved > 0) Log.i(TAG, "Migrated $moved analysis artifact(s) from cache to files")
    }
}
