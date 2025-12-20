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
                cues.add(DJCue(0f, "B", 1f, "jump"))
                cues.add(DJCue(totalBeats, "A", 0f, "jump"))
            }
            "Cut" -> {
                val middle = totalBeats / 2f
                cues.add(DJCue(middle, "A", 0f, "jump"))
                cues.add(DJCue(middle, "B", 1f, "jump"))
                cues.add(DJCue(0f, "B", 0f, "jump"))
            }
            "Crossfade" -> {
                val fadeSteps = 16
                for (i in 0..fadeSteps) {
                    val fraction = i.toFloat() / fadeSteps
                    val beat = fraction * totalBeats
                    cues.add(DJCue(beat, "A", 1f - fraction, "ramp"))
                    cues.add(DJCue(beat, "B", fraction, "ramp"))
                }
            }
            else -> {
                cues.add(DJCue(0f, "B", 1f, "jump"))
                cues.add(DJCue(totalBeats, "A", 0f, "jump"))
            }
        }

        return cues.sortedBy { it.beat }
    }

    // Recommendation 1: Trust the Grid
    // Converts a timestamp (seconds) into a precise beat index (e.g., 4.5 beats)
    // using interpolation between the closest known beats.
    fun timeToBeat(beatGrid: List<Float>, timeSec: Float): Float {
        if (beatGrid.isEmpty()) return 0f

        // Find the insertion point
        val index = beatGrid.binarySearch(timeSec)
        if (index >= 0) return index.toFloat() // Exact match

        val insertion = -(index + 1)

        // Interpolate between the surrounding beats
        if (insertion > 0 && insertion < beatGrid.size) {
            val t1 = beatGrid[insertion - 1]
            val t2 = beatGrid[insertion]

            // Percentage progress between previous beat and next beat
            val fraction = (timeSec - t1) / (t2 - t1)
            return (insertion - 1) + fraction
        }

        // Fallback: Estimate based on the last known interval if outside the grid
        if (insertion >= beatGrid.size && beatGrid.size > 1) {
            val lastInterval = beatGrid.last() - beatGrid[beatGrid.size - 2]
            val diff = timeSec - beatGrid.last()
            return (beatGrid.size - 1) + (diff / lastInterval)
        }

        return insertion.toFloat()
    }

    // Recommendation 1: Trust the Grid (Inverse)
    // Allows looking up exactly when beat #X happens, even if tempo changes.
    fun beatToTime(beatGrid: List<Float>, beatIndex: Float): Float {
        if (beatGrid.isEmpty()) return 0f

        val i = beatIndex.toInt()
        val fraction = beatIndex - i

        if (i >= 0 && i < beatGrid.size - 1) {
            val t1 = beatGrid[i]
            val t2 = beatGrid[i+1]
            return t1 + (t2 - t1) * fraction
        }

        // Fallback for out of bounds
        if (i >= beatGrid.size - 1 && beatGrid.size > 1) {
            val lastInterval = beatGrid.last() - beatGrid[beatGrid.size - 2]
            return beatGrid.last() + (beatIndex - (beatGrid.size - 1)) * lastInterval
        }

        return if (beatGrid.isNotEmpty()) beatGrid[0] else 0f
    }
}