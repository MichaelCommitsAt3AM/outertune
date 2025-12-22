package com.dd3boh.outertune.utils.analysis

import kotlin.math.sqrt

/**
 * Result container for bar detection.
 * @param downbeatOffset The index (0..timeSignature-1) of the beat identified as the "one".
 * @param confidence A value between 0.0 and 1.0 indicating how distinct the winner was.
 */
data class BarDetectionResult(
    val downbeatOffset: Int,
    val confidence: Float
)

object BarDetector {

    /**
     * Detects the downbeat offset based on energy contrast within bars.
     *
     * @param pcmData Raw mono audio data.
     * @param sampleRate Sample rate of the audio data.
     * @param beatGrid Detected beat timestamps in seconds.
     * @param timeSignature Number of beats per bar (default 4).
     */
    fun detect(
        pcmData: FloatArray,
        sampleRate: Int,
        beatGrid: List<Float>,
        timeSignature: Int = 4
    ): BarDetectionResult {
        if (beatGrid.isEmpty() || pcmData.isEmpty()) {
            return BarDetectionResult(0, 0f)
        }

        // 1. Pre-calculate RMS energy for every beat in the grid
        // Window size: 50ms around the beat transient
        val windowSamples = (sampleRate * 0.05).toInt()
        val energies = FloatArray(beatGrid.size)
        var globalMaxEnergy = 0f

        for (i in beatGrid.indices) {
            val beatTime = beatGrid[i]
            val centerIndex = (beatTime * sampleRate).toInt()

            val start = (centerIndex - windowSamples / 2).coerceAtLeast(0)
            val end = (centerIndex + windowSamples / 2).coerceAtMost(pcmData.size)

            if (start >= end) {
                energies[i] = 0f
                continue
            }

            var sumSquares = 0.0
            for (j in start until end) {
                val s = pcmData[j]
                sumSquares += s * s
            }
            val rms = sqrt(sumSquares / (end - start)).toFloat()

            energies[i] = rms
            if (rms > globalMaxEnergy) {
                globalMaxEnergy = rms
            }
        }

        // 2. Score each candidate offset (0, 1, 2, 3)
        // Scores are accumulated based on "Contrast": (Energy[One] - AvgEnergy[OtherBeats])
        val scores = FloatArray(timeSignature) { 0f }

        // Threshold to ignore quiet sections (intros, breakdowns)
        // preventing noise from affecting the decision.
        val silenceThreshold = globalMaxEnergy * 0.15f

        for (offset in 0 until timeSignature) {
            var totalContrast = 0f
            var validBarsCount = 0

            // Iterate through the grid with a stride of 'timeSignature', starting at 'offset'
            for (i in offset until energies.size step timeSignature) {
                val downbeatEnergy = energies[i]

                // Skip this bar analysis if the "One" candidate is too quiet
                if (downbeatEnergy < silenceThreshold) continue

                // Calculate average energy of the 'offbeats' in this specific bar
                var offbeatSum = 0f
                var offbeatCount = 0

                for (j in 1 until timeSignature) {
                    if (i + j < energies.size) {
                        offbeatSum += energies[i + j]
                        offbeatCount++
                    }
                }

                if (offbeatCount > 0) {
                    val avgOffbeatEnergy = offbeatSum / offbeatCount

                    // A true downbeat should be louder than the offbeats.
                    // This adds positive score if Downbeat > Average, negative if quieter.
                    totalContrast += (downbeatEnergy - avgOffbeatEnergy)
                    validBarsCount++
                }
            }

            // Normalize by number of bars processed to treat songs of different lengths equally
            scores[offset] = if (validBarsCount > 0) totalContrast else -1f
        }

        // 3. Determine Winner and Confidence
        // Find the index with the highest score
        var bestOffset = 0
        var maxScore = -Float.MAX_VALUE

        for (i in scores.indices) {
            if (scores[i] > maxScore) {
                maxScore = scores[i]
                bestOffset = i
            }
        }

        // Calculate Confidence: (BestScore - SecondBestScore) / (Range of Scores)
        // This measures "how much better" the winner is compared to the runner-up.
        val sortedScores = scores.sortedDescending()
        val winner = sortedScores[0]
        val runnerUp = sortedScores.getOrElse(1) { winner } // If only 1 beat per bar?

        // If the winner is negative (no clear downbeat pattern found or silence), confidence is 0
        val confidence = if (winner > 0) {
            // Simple margin calculation normalized roughly to 0-1 range
            // If winner is 100 and runnerUp is 50, confidence is 0.5.
            ((winner - runnerUp) / winner).coerceIn(0f, 1f)
        } else {
            0f
        }

        return BarDetectionResult(bestOffset, confidence)
    }
}