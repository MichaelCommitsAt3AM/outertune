package com.dd3boh.outertune.transition.engine

import com.dd3boh.outertune.utils.analysis.BinaryArtifacts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanonicalGridTest {

    @Test
    fun `sanitize sorts and drops duplicates and invalid beats`() {
        val out = CanonicalGrid.sanitize(listOf(1.0, 0.5, 0.5, 0.5004, Double.NaN, -0.2, 1.5))
        assertEquals(listOf(0.5, 1.0, 1.5), out)
    }

    @Test
    fun `alignment moves the grid onto the waveform's beats`() {
        val durationSec = 60.0
        val pointsPerSecond = 100
        val waveform = FloatArray((durationSec * pointsPerSecond).toInt()) { 0.05f }
        // Real beats every 0.5 s, starting at 0.1 s
        var t = 0.1
        while (t < durationSec) {
            waveform[(t * pointsPerSecond).toInt()] = 1f
            t += 0.5
        }
        val grid = List(119) { it * 0.5 }

        val aligned = CanonicalGrid.alignToWaveform(grid, waveform, durationSec)
        assertEquals(0.1, aligned.first(), 0.01)
        assertEquals(0.5, aligned[1] - aligned[0], 1e-9)
    }

    @Test
    fun `build is deterministic`() {
        val raw = List(200) { 0.3 + it * 60.0 / 124 }
        val waveform = FloatArray(10_000) { if (it % 48 == 0) 1f else 0.1f }
        val a = CanonicalGrid.build(raw, 124f, 124f, 100.0, waveform)
        val b = CanonicalGrid.build(raw, 124f, 124f, 100.0, waveform)
        assertEquals(a, b)
        assertTrue(a.zipWithNext().all { (x, y) -> y > x })
    }

    @Test
    fun `bpm-only grid covers the song`() {
        val grid = CanonicalGrid.fromBpm(120f, 0.25, 10.0)
        assertEquals(0.25, grid.first(), 0.0)
        assertEquals(20, grid.size)
        assertTrue(CanonicalGrid.fromBpm(0f, 0.0, 10.0).isEmpty())
    }

    @Test
    fun `binary grid and waveform round trip`() {
        val dir = createTempDir()
        try {
            val gridFile = File(dir, "g.bin")
            val beats = doubleArrayOf(0.1, 0.6, 1.1)
            BinaryArtifacts.writeGrid(gridFile, BinaryArtifacts.StoredGrid(CanonicalGrid.VERSION, 120f, 124f, beats))
            val read = BinaryArtifacts.readGrid(gridFile)!!
            assertEquals(CanonicalGrid.VERSION, read.version)
            assertEquals(120f, read.analysisBpm, 0f)
            assertEquals(124f, read.displayBpm, 0f)
            assertArrayEquals(beats, read.beatsSec, 0.0)

            val wfFile = File(dir, "w.bin")
            val samples = floatArrayOf(0f, 0.5f, 1f)
            BinaryArtifacts.writeWaveform(wfFile, samples)
            assertArrayEquals(samples, BinaryArtifacts.readWaveform(wfFile), 0f)

            // Wrong format and truncated files are rejected, not misread.
            assertNull(BinaryArtifacts.readGrid(wfFile))
            gridFile.writeBytes(gridFile.readBytes().copyOf(20))
            assertNull(BinaryArtifacts.readGrid(gridFile))
            assertNull(BinaryArtifacts.readGrid(File(dir, "missing.bin")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
