package com.dd3boh.outertune.transition.engine

import android.media.audiofx.Equalizer
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.transition.model.EffectMode
import kotlin.math.abs
import com.dd3boh.outertune.utils.DebugLog as Log

/** Applies a [DeckState]'s bass and filter values to a deck. */
interface DeckEffects {
    fun apply(player: ExoPlayer, state: DeckState, effectMode: EffectMode)

    /** Back to flat. */
    fun reset(player: ExoPlayer)

    fun releaseAll()
}

/**
 * [DeckEffects] on the platform's graphic equalizer, one per player (keyed by player, so effects
 * never end up on the wrong deck after a swap). The equalizer only approximates a DJ mixer's
 * bass kill and filters, and each band write is an IPC call, so bands are only written when
 * their level actually changes.
 */
class EqualizerDeckEffects : DeckEffects {
    private val equalizers = HashMap<ExoPlayer, DeckEqualizer>()

    override fun apply(player: ExoPlayer, state: DeckState, effectMode: EffectMode) {
        equalizerFor(player)?.apply(state, effectMode)
    }

    override fun reset(player: ExoPlayer) {
        equalizers[player]?.reset()
    }

    override fun releaseAll() {
        equalizers.values.forEach { it.release() }
        equalizers.clear()
    }

    private fun equalizerFor(player: ExoPlayer): DeckEqualizer? {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return null

        equalizers[player]?.let { existing ->
            if (existing.sessionId == sessionId) return existing
            existing.release()
            equalizers.remove(player)
        }
        return try {
            DeckEqualizer(Equalizer(0, sessionId).apply { enabled = true }, sessionId)
                .also { equalizers[player] = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init EQ", e)
            null
        }
    }

    private class DeckEqualizer(val equalizer: Equalizer, val sessionId: Int) {
        private val bands = equalizer.numberOfBands.toInt()
        private val minLevel = equalizer.bandLevelRange[0].toInt()
        private val written = IntArray(bands)
        private val target = IntArray(bands)

        fun apply(state: DeckState, effectMode: EffectMode) {
            if (bands < 1) return
            target.fill(0)

            // Bass cut on the lowest bands
            val bassCut = (minLevel * (1f - state.bass)).toInt()
            target[0] = bassCut
            if (bands > 1) target[1] = (bassCut * 0.8).toInt()

            val cut = (minLevel * (1f - state.filterHigh)).toInt()
            if (effectMode.isLowPass) {
                target[bands - 1] = cut
                if (bands > 2) target[bands - 2] = (cut * 0.7).toInt()
            } else if (effectMode.isHighPass) {
                // Whichever of bass cut and high-pass is stronger wins on the low bands.
                target[0] = minOf(target[0], cut)
                if (bands > 1) target[1] = minOf(target[1], cut)
                if (bands > 2) target[2] = (cut * 0.5).toInt()
            }
            write()
        }

        fun reset() {
            target.fill(0)
            write()
        }

        private fun write() {
            for (band in 0 until bands) {
                val returningToFlat = target[band] == 0 && written[band] != 0
                if (abs(target[band] - written[band]) < MixTuning.EQ_MIN_LEVEL_STEP_MB && !returningToFlat) continue
                try {
                    equalizer.setBandLevel(band.toShort(), target[band].toShort())
                    written[band] = target[band]
                } catch (_: Exception) {
                    // EQ failures are non-critical
                }
            }
        }

        fun release() {
            try { equalizer.release() } catch (_: Exception) {}
        }
    }

    private companion object {
        const val TAG = "DeckEffects"
    }
}
