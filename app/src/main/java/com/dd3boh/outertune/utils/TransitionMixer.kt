package com.dd3boh.outertune.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Holds the instantaneous state of a deck (Track A or B) at a specific point in time.
 * All values are normalized 0.0f to 1.0f.
 */
data class DeckState(
    val volume: Float = 1f,
    val bass: Float = 1f,      // 1.0 = Full Bass, 0.0 = No Bass
    val filterHigh: Float = 1f, // 1.0 = Open, 0.0 = Filter fully closed
    val sidechainActive: Boolean = false // Debug flag for UI
)

/**
 * Pure mapping from (track, progress, modes) to a [DeckState]. Shared by the editor preview and
 * playlist playback so both shape the transition identically.
 *
 * Mode names are matched case-insensitively: they are stored as free-form strings and the
 * historical names are inconsistently cased ("Low pass in" vs "Low Pass out").
 */
object TransitionMixer {

    /** True when [effectMode] is one of the low-pass effects. */
    fun isLowPass(effectMode: String): Boolean = effectMode.startsWith("Low pass", ignoreCase = true)

    /** True when [effectMode] is one of the high-pass effects. */
    fun isHighPass(effectMode: String): Boolean = effectMode.startsWith("High pass", ignoreCase = true)

    /**
     * @param positionA Track A's current media position (seconds). Only needed for sidechain.
     * @param positionB Track B's current media position (seconds). Only needed for sidechain.
     */
    fun getMixState(
        track: String, // "A" or "B"
        progress: Float, // 0.0 (Start of Zone) to 1.0 (End of Zone)
        volMode: String,
        eqMode: String,
        effectMode: String,
        positionA: Double = 0.0,
        positionB: Double = 0.0,
        beatGridA: List<Double>? = null,
        beatGridB: List<Double>? = null
    ): DeckState {
        // Clamp progress to ensure we don't go out of bounds
        val p = progress.coerceIn(0f, 1f)

        // --- 0. Dynamic Sidechain Logic ---
        var sidechainVol = 1f
        var sidechainBass = 1f
        var isSidechaining = false

        if (volMode.contains("Sidechain", true)) {
            // Each deck ducks under the *other* deck's beats. The other deck's grid is in its own
            // media time, so it must be compared against the other deck's position.
            val duckAmount = when {
                track == "A" && beatGridB != null -> calculateSidechainEnvelope(positionB, beatGridB)
                track == "B" && beatGridA != null -> calculateSidechainEnvelope(positionA, beatGridA)
                else -> 0f
            }

            if (duckAmount > 0.05f) isSidechaining = true

            // Duck volume by up to 80% on the beat.
            sidechainVol = 1f - (duckAmount * 0.8f)

            // Bass ducking is capped at 80% suppression (floor 0.2, about -14 dB) to avoid a hard
            // jump while still getting the sub out of the way.
            val duckFloor = 0.2f
            sidechainBass = 1f - (duckAmount * (1f - duckFloor))
        }

        // 1. Calculate Volume
        val vol = calculateVolume(track, p, volMode) * sidechainVol

        // 2. Calculate Bass (EQ) - Combine Static EQ modes with Dynamic Sidechain
        val staticBass = calculateBass(track, p, eqMode)
        val bass = staticBass * sidechainBass // Multiplicative: if either wants to cut, it cuts.

        // 3. Calculate Filter (Effect)
        val filter = calculateFilter(track, p, effectMode)

        return DeckState(vol, bass, filter, isSidechaining)
    }

    // Asymmetric ducking envelope (0.0 = no ducking, 1.0 = max ducking) around the beat of
    // [grid] nearest to [now]. Timeline relative to the beat (diff = now - beatTime):
    //   -10ms to -5ms : attack ramp 0 -> 1
    //    -5ms to  0ms : hold at 1 (lookahead safety zone)
    //     0ms to release : release ramp 1 -> 0 (release is tempo-locked, 30% of a beat)
    private fun calculateSidechainEnvelope(now: Double, grid: List<Double>): Float {
        if (grid.isEmpty()) return 0f

        val avgInterval = if (grid.size > 1) {
            (grid.last() - grid.first()) / (grid.size - 1)
        } else 0.5 // Default 120 BPM if 1 beat only
        val releaseDuration = avgInterval * 0.30

        // The two beats around `now` are the only candidates; find them by binary search.
        val ip = grid.binarySearch(now)
        val next = if (ip >= 0) ip else -(ip + 1)
        val prev = next - 1
        val diffPrev = if (prev >= 0) now - grid[prev] else Double.MAX_VALUE // >= 0
        val diffNext = if (next < grid.size) now - grid[next] else -Double.MAX_VALUE // <= 0

        val closestDiff = when {
            diffNext > -0.010 && abs(diffNext) <= abs(diffPrev) -> diffNext
            diffPrev <= releaseDuration -> diffPrev
            else -> return 0f
        }

        return if (closestDiff >= 0) {
            val progress = (closestDiff / releaseDuration).coerceIn(0.0, 1.0)
            (1.0 - progress).toFloat()
        } else if (closestDiff > -0.005) {
            1.0f
        } else {
            val attackStart = -0.010
            val attackEnd = -0.005
            ((closestDiff - attackStart) / (attackEnd - attackStart)).coerceIn(0.0, 1.0).toFloat()
        }
    }

    // --- Volume Logic ---
    private fun calculateVolume(track: String, p: Float, mode: String): Float {
        return when (mode.lowercase()) {
            "cut in fade out" -> {
                // B starts full immediately.
                // A plays full until 50%, then fades out.
                if (track == "B") 1f
                else if (p < 0.5f) 1f else 1f - ((p - 0.5f) * 2f)
            }
            "crossfade" -> {
                // Equal-power crossfade: keeps perceived loudness constant through the middle,
                // where a linear fade dips by about 3 dB.
                val angle = p * (PI / 2).toFloat()
                if (track == "A") cos(angle) else sin(angle)
            }
            "cut" -> {
                // Hard swap at 50%
                if (track == "A") if (p < 0.5f) 1f else 0f
                else if (p >= 0.5f) 1f else 0f
            }
            // "overlap", "dynamic sidechain": both decks at full volume; sidechain attenuation
            // is handled in getMixState.
            else -> 1f
        }
    }

    // --- EQ Logic (Bass Swap) ---
    private fun calculateBass(track: String, p: Float, mode: String): Float {
        return when (mode.lowercase()) {
            "centre bass swap" -> {
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
            "end bass swap" -> {
                // B starts without bass and gains it over the last 10%; A keeps it until then.
                if (track == "A") if (p < 0.9f) 1f else 1f - ((p - 0.9f) * 10f)
                else if (p < 0.9f) 0f else ((p - 0.9f) * 10f)
            }
            "onset bass swap" -> {
                // Immediately at start: A loses bass, B enters with bass.
                if (track == "A") 0f else 1f
            }
            else -> 1f // No EQ changes
        }
    }

    // --- Effect Logic (High/Low Pass) ---
    private fun calculateFilter(track: String, p: Float, mode: String): Float {
        // Filter openness: 1.0 = Open, 0.0 = Closed
        return when (mode.lowercase()) {
            // B starts closed and opens up. A is untouched.
            "low pass in", "high pass in" -> if (track == "B") p else 1f
            // A starts open and closes from 50%. B is untouched.
            "low pass out", "high pass out" ->
                if (track == "A") if (p < 0.5f) 1f else 1f - ((p - 0.5f) * 2f) else 1f
            else -> 1f
        }
    }
}
