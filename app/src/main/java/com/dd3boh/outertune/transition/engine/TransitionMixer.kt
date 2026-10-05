package com.dd3boh.outertune.transition.engine

import com.dd3boh.outertune.transition.model.Deck
import com.dd3boh.outertune.transition.model.EffectMode
import com.dd3boh.outertune.transition.model.EqMode
import com.dd3boh.outertune.transition.model.OverlapMode
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
) {
    companion object {
        /** A deck with nothing applied. */
        val NEUTRAL = DeckState()
    }
}

/**
 * Pure mapping from (deck, progress, modes) to a [DeckState]: the shape of the transition.
 */
object TransitionMixer {

    /**
     * @param progress 0.0 (start of zone) to 1.0 (end of zone); clamped
     * @param positionA Track A's current media position (seconds). Only needed for sidechain.
     * @param positionB Track B's current media position (seconds). Only needed for sidechain.
     */
    fun getMixState(
        deck: Deck,
        progress: Float,
        overlapMode: OverlapMode,
        eqMode: EqMode,
        effectMode: EffectMode,
        positionA: Double = 0.0,
        positionB: Double = 0.0,
        beatGridA: List<Double>? = null,
        beatGridB: List<Double>? = null
    ): DeckState {
        val p = progress.coerceIn(0f, 1f)

        // --- 0. Dynamic Sidechain Logic ---
        var sidechainVol = 1f
        var sidechainBass = 1f
        var isSidechaining = false

        if (overlapMode == OverlapMode.DYNAMIC_SIDECHAIN) {
            // Each deck ducks under the *other* deck's beats. The other deck's grid is in its own
            // media time, so it must be compared against the other deck's position.
            val duckAmount = when {
                deck == Deck.A && beatGridB != null -> calculateSidechainEnvelope(positionB, beatGridB)
                deck == Deck.B && beatGridA != null -> calculateSidechainEnvelope(positionA, beatGridA)
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

        val vol = calculateVolume(deck, p, overlapMode) * sidechainVol
        // Multiplicative: if either the bass swap or the sidechain wants to cut, it cuts.
        val bass = calculateBass(deck, p, eqMode) * sidechainBass
        val filter = calculateFilter(deck, p, effectMode)

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

    private fun calculateVolume(deck: Deck, p: Float, mode: OverlapMode): Float = when (mode) {
        OverlapMode.CUT_IN_FADE_OUT ->
            if (deck == Deck.B) 1f else if (p < 0.5f) 1f else 1f - ((p - 0.5f) * 2f)
        OverlapMode.CROSSFADE -> {
            // Equal-power crossfade: keeps perceived loudness constant through the middle,
            // where a linear fade dips by about 3 dB.
            val angle = p * (PI / 2).toFloat()
            if (deck == Deck.A) cos(angle) else sin(angle)
        }
        OverlapMode.CUT ->
            if (deck == Deck.A) (if (p < 0.5f) 1f else 0f) else (if (p >= 0.5f) 1f else 0f)
        // Sidechain attenuation is applied in getMixState.
        OverlapMode.OVERLAP, OverlapMode.DYNAMIC_SIDECHAIN -> 1f
    }

    private fun calculateBass(deck: Deck, p: Float, mode: EqMode): Float = when (mode) {
        EqMode.CENTRE_BASS_SWAP -> {
            val start = 0.4f
            val end = 0.6f
            when {
                p < start -> if (deck == Deck.A) 1f else 0f
                p > end -> if (deck == Deck.A) 0f else 1f
                else -> {
                    val localP = (p - start) / (end - start)
                    if (deck == Deck.A) 1f - localP else localP
                }
            }
        }
        EqMode.END_BASS_SWAP ->
            if (deck == Deck.A) (if (p < 0.9f) 1f else 1f - ((p - 0.9f) * 10f))
            else (if (p < 0.9f) 0f else ((p - 0.9f) * 10f))
        EqMode.ONSET_BASS_SWAP -> if (deck == Deck.A) 0f else 1f
        EqMode.NONE -> 1f
    }

    private fun calculateFilter(deck: Deck, p: Float, mode: EffectMode): Float = when (mode) {
        // B starts closed and opens up. A is untouched.
        EffectMode.LOW_PASS_IN, EffectMode.HIGH_PASS_IN -> if (deck == Deck.B) p else 1f
        // A starts open and closes from 50%. B is untouched.
        EffectMode.LOW_PASS_OUT, EffectMode.HIGH_PASS_OUT ->
            if (deck == Deck.A) (if (p < 0.5f) 1f else 1f - ((p - 0.5f) * 2f)) else 1f
        EffectMode.NONE -> 1f
    }
}
