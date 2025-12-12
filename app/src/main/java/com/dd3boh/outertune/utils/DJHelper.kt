package com.dd3boh.outertune.utils

data class DJCue(
    val beat: Float,      // Beat index
    val action: String,   // "fade_in", "fade_out", "start", "end"
    val volume: Float     // Volume at this beat (0..1)
)

data class TrackState(
    val beatGrid: List<Float>
)

object DJHelper {

    /**
     * Generates DJ cues for a smooth transition between trackA and trackB
     * @param bars Number of bars in transition
     * @param fadeBeats Number of beats for fade-in/out
     */
    fun generateDJCues(
        trackA: TrackState,
        trackB: TrackState,
        bars: Int,
        fadeBeats: Int = 4
    ): List<DJCue> {
        val cues = mutableListOf<DJCue>()
        val totalBeats = bars * 4
        val lastBeatA = trackA.beatGrid.size - 1
        val startBeatA = (lastBeatA - totalBeats).coerceAtLeast(0)
        val startBeatB = 0

        // Track A: start of transition
        cues.add(DJCue(startBeatA.toFloat(), "start", 1f))

        // Track B: fade-in
        for (i in 0..fadeBeats) {
            val beat = startBeatB + i.toFloat()
            val vol = i.toFloat() / fadeBeats
            cues.add(DJCue(beat, "fade_in", vol))
        }

        // Track A: fade-out
        for (i in 0..fadeBeats) {
            val beat = startBeatA + totalBeats - fadeBeats + i.toFloat()
            val vol = 1f - (i.toFloat() / fadeBeats)
            cues.add(DJCue(beat, "fade_out", vol))
        }

        // Track B: full volume after fade-in
        cues.add(DJCue(startBeatB + fadeBeats.toFloat(), "full_volume", 1f))

        // Track B: end of transition
        cues.add(DJCue(startBeatB + totalBeats.toFloat(), "end", 1f))

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
