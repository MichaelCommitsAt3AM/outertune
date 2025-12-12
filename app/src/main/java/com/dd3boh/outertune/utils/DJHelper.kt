package com.dd3boh.outertune.utils

/**
 * @param beat Relative beat index (0 = start of transition zone)
 * @param track "A" or "B"
 * @param volume Target volume (0f to 1f)
 * @param type "jump" (instant change) or "ramp" (gradual fade from previous volume)
 */
data class DJCue(
    val beat: Float,
    val track: String,
    val volume: Float,
    val type: String = "jump"
)

object DJHelper {

    fun generateDJCues(
        bars: Int,
        mode: String = "Overlap" // "Overlap", "Crossfade", "Cut"
    ): List<DJCue> {
        val cues = mutableListOf<DJCue>()
        val totalBeats = bars * 4f

        when (mode) {
            "Overlap" -> {
                // 1. Track B starts immediately at full volume at the start (Beat 0)
                cues.add(DJCue(0f, "B", 1f, "jump"))

                // 2. Track A plays fully until the end, then cuts to silence (Beat End)
                cues.add(DJCue(totalBeats, "A", 0f, "jump"))
            }
            "Cut" -> {
                // Switch exactly in the middle
                val middle = totalBeats / 2f
                cues.add(DJCue(middle, "A", 0f, "jump"))
                cues.add(DJCue(middle, "B", 1f, "jump"))
                // Ensure B is silent before the cut?
                // Usually handled by initial setup, but we can be explicit:
                cues.add(DJCue(0f, "B", 0f, "jump"))
            }
            "Crossfade" -> {
                // Linear Crossfade over the whole duration
                val fadeSteps = 16 // Resolution of fade
                for (i in 0..fadeSteps) {
                    val fraction = i.toFloat() / fadeSteps
                    val beat = fraction * totalBeats

                    // A goes 1 -> 0
                    cues.add(DJCue(beat, "A", 1f - fraction, "ramp"))
                    // B goes 0 -> 1
                    cues.add(DJCue(beat, "B", fraction, "ramp"))
                }
            }
            else -> {
                // Default fallback (Overlap)
                cues.add(DJCue(0f, "B", 1f, "jump"))
                cues.add(DJCue(totalBeats, "A", 0f, "jump"))
            }
        }

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