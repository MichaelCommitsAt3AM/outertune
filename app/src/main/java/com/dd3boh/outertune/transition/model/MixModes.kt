package com.dd3boh.outertune.transition.model

import androidx.annotation.StringRes
import com.dd3boh.outertune.R

/** The two decks of a transition: A is outgoing, B is incoming. */
enum class Deck { A, B }

/** How the two decks' volumes are shaped across the transition zone. */
enum class OverlapMode(@StringRes val label: Int) {
    /** Both decks at full volume. */
    OVERLAP(R.string.mix_overlap_overlap),
    /** Equal-power fade from A to B. */
    CROSSFADE(R.string.mix_overlap_crossfade),
    /** Hard swap at the middle of the zone. */
    CUT(R.string.mix_overlap_cut),
    /** B starts at full volume; A fades out over the second half. */
    CUT_IN_FADE_OUT(R.string.mix_overlap_cut_in_fade_out),
    /** Both at full volume, each ducking under the other's beats. */
    DYNAMIC_SIDECHAIN(R.string.mix_overlap_dynamic_sidechain);

    companion object {
        /** Parses a stored value: an enum name, or one of the historical free-form labels. */
        fun fromStored(value: String?): OverlapMode {
            val key = value?.trim()?.uppercase()?.replace(' ', '_') ?: return OVERLAP
            return entries.firstOrNull { it.name == key } ?: OVERLAP
        }
    }
}

/** How bass is handed over from A to B. */
enum class EqMode(@StringRes val label: Int) {
    NONE(R.string.mix_none),
    /** Quick bass swap around the middle of the zone. */
    CENTRE_BASS_SWAP(R.string.mix_eq_centre_bass_swap),
    /** B gets its bass only in the last 10% of the zone. */
    END_BASS_SWAP(R.string.mix_eq_end_bass_swap),
    /** A loses its bass as soon as B comes in. */
    ONSET_BASS_SWAP(R.string.mix_eq_onset_bass_swap);

    companion object {
        fun fromStored(value: String?): EqMode {
            val key = value?.trim()?.uppercase()?.replace(' ', '_') ?: return NONE
            return entries.firstOrNull { it.name == key } ?: NONE
        }
    }
}

/** Filter sweep applied during the zone. */
enum class EffectMode(@StringRes val label: Int, val isLowPass: Boolean, val isHighPass: Boolean) {
    NONE(R.string.mix_none, false, false),
    /** B opens up from a closed low-pass. */
    LOW_PASS_IN(R.string.mix_effect_low_pass_in, true, false),
    /** A closes into a low-pass over the second half. */
    LOW_PASS_OUT(R.string.mix_effect_low_pass_out, true, false),
    /** B opens up from a closed high-pass. */
    HIGH_PASS_IN(R.string.mix_effect_high_pass_in, false, true),
    /** A closes into a high-pass over the second half. */
    HIGH_PASS_OUT(R.string.mix_effect_high_pass_out, false, true);

    companion object {
        fun fromStored(value: String?): EffectMode {
            val key = value?.trim()?.uppercase()?.replace(' ', '_') ?: return NONE
            return entries.firstOrNull { it.name == key } ?: NONE
        }
    }
}
