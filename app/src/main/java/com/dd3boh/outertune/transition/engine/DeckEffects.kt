package com.dd3boh.outertune.transition.engine

import androidx.media3.exoplayer.ExoPlayer

/** Shapes a deck's sound (gain, bass, filter) through a transition. */
interface DeckEffects {
    /** Starts following [automation] on [player], or goes back to untouched sound with null. */
    fun setAutomation(player: ExoPlayer, automation: DeckAutomation?)
}

/**
 * [DeckEffects] through each deck's own [DeckAudioProcessor]: sample-accurate, the same on every
 * device, and independent of the system equalizer (which stays free for the user's own EQ app).
 */
class ProcessorDeckEffects(private val factory: DeckFactory) : DeckEffects {
    override fun setAutomation(player: ExoPlayer, automation: DeckAutomation?) {
        factory.processorFor(player)?.automation = automation
    }
}
