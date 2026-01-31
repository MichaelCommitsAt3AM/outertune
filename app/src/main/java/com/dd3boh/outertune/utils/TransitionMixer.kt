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
    val filterHigh: Float = 1f, // 1.0 = Open, 0.0 = Low Pass (Muffled)
    val sidechainActive: Boolean = false // Debug flag for UI
)

object TransitionMixer {

    fun getMixState(
        track: String, // "A" or "B"
        progress: Float, // 0.0 (Start of Zone) to 1.0 (End of Zone)
        volMode: String,
        eqMode: String,
        effectMode: String,
        currentTimestamp: Double = 0.0,
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
            var duckAmount = 0f
            
            // Determine "Duck Amount" (0.0 = Clashing, need to duck)
            if (track == "A" && beatGridB != null) {
                duckAmount = calculateSidechainEnvelope(currentTimestamp, beatGridB)
            } else if (track == "B" && beatGridA != null) {
                duckAmount = calculateSidechainEnvelope(currentTimestamp, beatGridA)
            }
            
            if (duckAmount > 0.05f) isSidechaining = true
            
            // 20% Volume Ducking (Preserves Mids/Highs)
            sidechainVol = 1f - (duckAmount * 0.8f) 
            
            // 100% Bass Ducking (Kills Mud) -> NOW CAPPED at 80% suppression (Floor 0.2)
            // We use a floor of 0.2 (-14dB) to avoid DC jump / phase reset while ensuring sub is "gone".
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
    
    // Calculates asymmetric envelope (0.0 to 1.0)
    // 0.0 = No Ducking
    // 1.0 = Max Ducking
    private fun calculateSidechainEnvelope(now: Double, grid: List<Double>): Float {
        // Optimization: Find CLOSEST beat
        // We assume grid is sorted.
        
        // 1. Calculate Tempo (Beat Interval) from Grid
        // Only need a local estimate.
        val avgInterval = if (grid.size > 1) {
            (grid.last() - grid.first()) / (grid.size - 1)
        } else 0.5 // Default 120 BPM if 1 beat only
        
        val releaseDuration = avgInterval * 0.30 // Tempo-locked Release
        
        val windowStart = now - releaseDuration 
        val windowEnd = now + 0.010   // 10ms Pre-Duck (Attack Start)
        
        // Find closest beat within this window
        var closestDiff = Double.MAX_VALUE
        
        for (beatTime in grid) {
           if (beatTime < windowStart) continue
           if (beatTime > windowEnd) break 
           
           val diff = now - beatTime 
           if (kotlin.math.abs(diff) < kotlin.math.abs(closestDiff)) {
               closestDiff = diff
           }
        }
        
        if (closestDiff == Double.MAX_VALUE) return 0f
        
        // Apply Asymmetric Envelope
        // closestDiff is (now - beatTime)
        
        // Apply Trapezoid Envelope with Pre-Beat Shift (Virtual Lookahead)
        
        // Timeline relative to Beat (diff = now - beatTime):
        // Before -10ms: 0.0
        // -10ms to -5ms: Attack Ramp (0.0 -> 1.0)
        // -5ms to 0ms:   Hold Max (1.0) -> This is the "Lookahead" safety zone
        // 0ms to Release: Release Ramp (1.0 -> 0.0)
        
        return if (closestDiff >= 0) {
            // Post-Beat (Release Phase) - 0ms to releaseDuration
            val progress = (closestDiff / releaseDuration).coerceIn(0.0, 1.0)
            (1.0 - progress).toFloat() // 1.0 -> 0.0
        } else {
            // Pre-Beat phase (negative diff)
            val t = closestDiff // e.g., -0.007
            
            if (t > -0.005) {
                // Hold Phase (-5ms to 0ms)
                1.0f 
            } else {
                // Attack Phase (-10ms to -5ms)
                // Map range [-0.010, -0.005] to [0.0, 1.0]
                val attackStart = -0.010
                val attackEnd = -0.005
                val progress = ((t - attackStart) / (attackEnd - attackStart)).coerceIn(0.0, 1.0)
                progress.toFloat()
            }
        }
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
            "Overlap", "Dynamic Sidechain" -> {
                // A plays full, B plays full (simple mixing)
                // Sidechain attenuation is handled in main function
                1f
            }
            else -> 1f
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