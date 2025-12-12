package com.dd3boh.outertune.utils

data class DJCue(
    val beat: Float,      // Relative beat index (0 = start of transition)
    val action: String,   // "fade_in", "fade_out", "start", "end"
    val volume: Float     // Volume at this beat (0..1)
)

data class TrackState(
    val beatGrid: List<Float>
)

object DJHelper {

    /**
     * Generates DJ cues for a smooth transition.
     * Cues are relative to the start of the transition (Beat 0).
     * @param bars Number of bars in transition
     * @param fadeBeats Number of beats for fade-in/out
     */
    fun generateDJCues(
        bars: Int,
        fadeBeats: Int = 4
    ): List<DJCue> {
        val cues = mutableListOf<DJCue>()
        val totalBeats = bars * 4

        // Start of transition (Beat 0)
        cues.add(DJCue(0f, "start", 1f))

        // Track B: Fade-in (0 -> 1)
        for (i in 0..fadeBeats) {
            val beat = i.toFloat()
            val vol = i.toFloat() / fadeBeats
            cues.add(DJCue(beat, "fade_in", vol))
        }

        // Track A: Fade-out (1 -> 0)
        // Starts at (totalBeats - fadeBeats)
        val fadeOutStart = totalBeats - fadeBeats
        for (i in 0..fadeBeats) {
            val beat = fadeOutStart + i.toFloat()
            val vol = 1f - (i.toFloat() / fadeBeats)
            cues.add(DJCue(beat, "fade_out", vol))
        }

        // Track B: Full volume checkpoint (after fade in)
        cues.add(DJCue(fadeBeats.toFloat(), "full_volume", 1f))

        // End of transition
        cues.add(DJCue(totalBeats.toFloat(), "end", 1f))

        return cues.sortedBy { it.beat }
    }

    fun timeToBeat(beatGrid: List<Float>, timeSec: Float): Float {
        if (beatGrid.isEmpty()) return 0f
        val index = beatGrid.binarySearch(timeSec)
        return if (index >= 0) index.toFloat() else {
            val insertion = -(index + 1)
            if (insertion > 0 && insertion < beatGrid.size) {
                val t1 = beatGrid[insertion - 1]
                val t2 = beatGrid[insertion]
                (insertion - 1) + (timeSec - t1) / (t2 - t1)
            } else insertion.toFloat()
        }
    }
}