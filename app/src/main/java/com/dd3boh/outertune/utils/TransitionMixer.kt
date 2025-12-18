package com.dd3boh.outertune.utils

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/**
 * Holds the instantaneous state of a deck (Track A or B) at a specific point in time.
 * All values are normalized 0.0f to 1.0f.
 */
data class DeckState(
    val volume: Float = 1f,
    val bass: Float = 1f,      // 1.0 = Full Bass, 0.0 = No Bass
    val filterHigh: Float = 1f // 1.0 = Open, 0.0 = Low Pass (Muffled)
)

object TransitionMixer {

    fun getMixState(
        track: String, // "A" or "B"
        progress: Float, // 0.0 (Start of Zone) to 1.0 (End of Zone)
        volMode: String,
        eqMode: String,
        effectMode: String
    ): DeckState {
        // Clamp progress to ensure we don't go out of bounds
        val p = progress.coerceIn(0f, 1f)

        // 1. Calculate Volume
        val vol = calculateVolume(track, p, volMode)

        // 2. Calculate Bass (EQ)
        val bass = calculateBass(track, p, eqMode)

        // 3. Calculate Filter (Effect)
        val filter = calculateFilter(track, p, effectMode)

        return DeckState(vol, bass, filter)
    }

    // --- Volume Logic ---
    private fun calculateVolume(track: String, p: Float, mode: String): Float {
        return when (mode) {
            "Cut In Fade Out" -> {
                // B starts full immediately.
                // A plays full until 50%, then fades out.
                if (track == "B") 1f
                else if (p < 0.5f) 1f else 1f - ((p - 0.5f) * 2f)
            }
            "Crossfade" -> {
                // Linear Crossfade
                if (track == "A") 1f - p else p
            }
            "Cut" -> {
                // Hard swap at 50%
                if (track == "A") if (p < 0.5f) 1f else 0f
                else if (p >= 0.5f) 1f else 0f
            }
            else -> { // "Overlap"
                // A plays full, B plays full (simple mixing)
                // A cuts at end, B started at beginning.
                1f
            }
        }
    }

    // --- EQ Logic (Bass Swap) ---
    private fun calculateBass(track: String, p: Float, mode: String): Float {
        return when (mode) {
            "Centre Bass swap" -> {
                // Swap bass frequencies in the middle (Fast X-Fade)
                val range = 0.1f // Width of the swap region
                val start = 0.5f - range
                val end = 0.5f + range

                if (p < start) if (track == "A") 1f else 0f
                else if (p > end) if (track == "A") 0f else 1f
                else {
                    // In the swap zone
                    val localP = (p - start) / (end - start)
                    if (track == "A") 1f - localP else localP
                }
            }
            "End Bass Swap" -> {
                // B starts without bass. Gains bass at the very end.
                // A keeps bass until the very end.
                // Let's swap at 90%
                if (track == "A") if (p < 0.9f) 1f else 1f - ((p - 0.9f) * 10f)
                else if (p < 0.9f) 0f else ((p - 0.9f) * 10f)
            }
            "Onset Bass Swap" -> {
                // Immediately at start: A loses bass, B enters with bass.
                if (track == "A") 0f else 1f
            }
            else -> 1f // No EQ changes
        }
    }

    // --- Effect Logic (High/Low Pass) ---
    private fun calculateFilter(track: String, p: Float, mode: String): Float {
        // We simulate filter openness: 1.0 = Open, 0.0 = Closed
        return when (mode) {
            "Low pass in" -> {
                // B starts closed (lows only), opens up. A is untouched.
                if (track == "B") p else 1f
            }
            "Low Pass out" -> {
                // A starts open, closes (fades to lows) starting at 50%. B untouched.
                if (track == "A") if (p < 0.5f) 1f else 1f - ((p - 0.5f) * 2f) else 1f
            }
            "High Pass in" -> {
                // We'll treat this logic similar to LPF for visualization,
                // but audio engine will interpret '0' as HPF closed.
                if (track == "B") p else 1f
            }
            "High Pass Out" -> {
                if (track == "A") if (p < 0.5f) 1f else 1f - ((p - 0.5f) * 2f) else 1f
            }
            else -> 1f
        }
    }
}