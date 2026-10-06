package com.dd3boh.outertune.transition.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Collects beatmatch quality numbers for one transition and logs a single summary line.
 *
 * This is the yardstick for tuning: phase error is in beats of Track B, so 0.05 is about 25 ms
 * at 120 BPM. Compare the summary from the editor preview with the one from a playlist to check
 * that both play the same transition the same way.
 */
class MixDiagnostics(private val tag: String, private val label: String) {
    private var unmuteError: Double? = null
    private var maxCrossfadeError = 0.0
    private var sumSqCrossfadeError = 0.0
    private var crossfadeSamples = 0
    private var speedChanges = 0
    private var reseeks = 0
    private val startedAt = System.currentTimeMillis()

    fun onUnmute(phaseError: Double) {
        if (unmuteError == null) unmuteError = phaseError
    }

    fun onCrossfadeSample(phaseError: Double) {
        val e = abs(phaseError)
        if (e > maxCrossfadeError) maxCrossfadeError = e
        sumSqCrossfadeError += e * e
        crossfadeSamples++
    }

    fun onSpeedChange() { speedChanges++ }

    fun onReseek() { reseeks++ }

    fun finish(outcome: String) {
        val rms = if (crossfadeSamples > 0) sqrt(sumSqCrossfadeError / crossfadeSamples) else 0.0
        // Logged in every build type: one line per transition, and needed to judge beatmatching
        // on real devices (profile builds included).
        android.util.Log.i(
            tag,
            "[$label] outcome=$outcome unmuteErr=${unmuteError?.let { "%.3f".format(it) } ?: "n/a"} " +
                    "maxErr=${"%.3f".format(maxCrossfadeError)} rmsErr=${"%.3f".format(rms)} " +
                    "speedChanges=$speedChanges reseeks=$reseeks " +
                    "took=${System.currentTimeMillis() - startedAt}ms"
        )
    }
}
