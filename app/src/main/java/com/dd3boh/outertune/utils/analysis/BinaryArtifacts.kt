package com.dd3boh.outertune.utils.analysis

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Compact binary files for analysis artifacts, replacing comma-separated text that had to be
 * re-parsed on every load. Each write goes to a temp file and is renamed into place, so a
 * reader never sees a half-written file.
 */
object BinaryArtifacts {
    private const val GRID_MAGIC = 0x4F544752 // "OTGR"
    private const val WAVEFORM_MAGIC = 0x4F545746 // "OTWF"

    /** A stored canonical beat grid and the inputs it was built from. */
    class StoredGrid(
        val version: Int,
        val analysisBpm: Float,
        val displayBpm: Float,
        val beatsSec: DoubleArray,
    )

    fun writeGrid(file: File, grid: StoredGrid) = atomicWrite(file) { out ->
        out.writeInt(GRID_MAGIC)
        out.writeInt(grid.version)
        out.writeFloat(grid.analysisBpm)
        out.writeFloat(grid.displayBpm)
        out.writeInt(grid.beatsSec.size)
        for (t in grid.beatsSec) out.writeDouble(t)
    }

    /** Null when the file is missing, from another format, or truncated. */
    fun readGrid(file: File): StoredGrid? = read(file) { input ->
        if (input.readInt() != GRID_MAGIC) return@read null
        val version = input.readInt()
        val analysisBpm = input.readFloat()
        val displayBpm = input.readFloat()
        val count = input.readInt()
        if (count < 0) return@read null
        StoredGrid(version, analysisBpm, displayBpm, DoubleArray(count) { input.readDouble() })
    }

    fun writeWaveform(file: File, samples: FloatArray) = atomicWrite(file) { out ->
        out.writeInt(WAVEFORM_MAGIC)
        out.writeInt(samples.size)
        for (s in samples) out.writeFloat(s)
    }

    fun readWaveform(file: File): FloatArray? = read(file) { input ->
        if (input.readInt() != WAVEFORM_MAGIC) return@read null
        val count = input.readInt()
        if (count < 0) return@read null
        FloatArray(count) { input.readFloat() }
    }

    private fun atomicWrite(file: File, block: (DataOutputStream) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use(block)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not write ${file.name}")
        }
    }

    private fun <T> read(file: File, block: (DataInputStream) -> T?): T? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use(block)
        } catch (_: IOException) {
            null
        }
    }
}
