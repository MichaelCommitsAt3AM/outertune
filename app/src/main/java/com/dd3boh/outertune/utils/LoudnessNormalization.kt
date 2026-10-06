package com.dd3boh.outertune.utils

import kotlin.math.min
import kotlin.math.pow

/**
 * Single source for the loudness-normalization gain, shared by normal playback and the mix
 * engine so a song keeps the same level when a transition hands it over.
 */
object LoudnessNormalization {

    /**
     * Gain used for a streamed song whose format row (and so its loudness) hasn't been fetched
     * yet. Starting quieter avoids a burst when the real factor arrives a moment later.
     */
    const val PENDING_FACTOR = 0.5f

    /**
     * @param enabled the user's normalization preference
     * @param formatKnown whether the song's format row exists
     * @param loudnessDb the song's integrated loudness, if known
     * @param isLocal local files never get a format row, so they are never "pending"
     */
    fun factor(enabled: Boolean, formatKnown: Boolean, loudnessDb: Double?, isLocal: Boolean): Float = when {
        !enabled -> 1f
        loudnessDb != null -> min(10f.pow(-loudnessDb.toFloat() / 20), 1f)
        !formatKnown && !isLocal -> PENDING_FACTOR
        else -> 1f
    }
}
