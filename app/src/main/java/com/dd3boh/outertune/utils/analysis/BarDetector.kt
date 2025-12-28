package com.dd3boh.outertune.utils.analysis

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Result container for bar detection.
 */
data class BarDetectionResult(
    val downbeatOffset: Int,
    val confidence: Float
)

object BarDetector {

    /**
     * Detects the downbeat offset using multi-band energy + onset features,
     * beat-phase histogram accumulation, adaptive windowing, and a softmax-based
     * confidence metric.
     *
     * Improvements over a simple RMS-only approach:
     *  - three approximate bands (low/mid/high) via lightweight IIR filters
     *  - an onset proxy computed from short-frame energy differences
     *  - adaptive window size based on median beat interval
     *  - beat-phase histogram (bucket by beatIndex % timeSignature)
     *  - softmax-based confidence (probability mass on winning bucket)
     *
     * Notes:
     *  - This is intentionally dependency-free and real-time friendly (single-pass filters).
     *  - For even higher quality, replace the simple filters/onset proxy with an FFT-based
     *    spectral flux and proper band filters.
     */
    fun detect(
        pcmData: FloatArray,
        sampleRate: Int,
        beatGrid: List<Float>,
        timeSignature: Int = 4,
        skipInitialBeats: Int = 2 // avoid bias from partial bars at start
    ): BarDetectionResult {
        if (beatGrid.isEmpty() || pcmData.isEmpty() || timeSignature <= 0) {
            return BarDetectionResult(0, 0f)
        }

        // --- 0. Quick guard: need at least a few beats ---
        if (beatGrid.size < timeSignature * 2) {
            // Not enough data to make a reliable decision
            return BarDetectionResult(0, 0f)
        }

        // --- 1. Estimate median beat interval to adapt window sizes ---
        val intervals = FloatArray(max(1, beatGrid.size - 1))
        for (i in 0 until beatGrid.size - 1) intervals[i] = beatGrid[i + 1] - beatGrid[i]
        val medianInterval = median(intervals)
        val beatDur = if (medianInterval > 0f) medianInterval else 0.5f // fall back to 0.5s

        // Window length tied to beat duration: fraction of beat, clamped
        val windowSec = clamp(beatDur * 0.20f, 0.03f, 0.20f) // 3..200 ms (adaptive)
        val windowSamples = max(4, (windowSec * sampleRate).toInt())

        // Short frame for onset proxy inside the window (e.g., 10 ms)
        val frameMs = 0.01f
        val frameSamples = max(2, (frameMs * sampleRate).toInt())

        // --- 2. Lightweight band-splitting using single-pole filters ---
        // Cutoff suggestions: low ~150Hz, high ~4000Hz
        val low = singlePoleLowpass(pcmData, sampleRate, 150f)   // bass/kick
        val high = singlePoleHighpass(pcmData, sampleRate, 4000f) // transients/high
        // mid approximated as remainder (pcm - low - high)
        val mid = FloatArray(pcmData.size)
        for (i in pcmData.indices) mid[i] = pcmData[i] - low[i] - high[i]

        // --- 3. Compute per-beat features (RMS per band + onset proxy) ---
        val nBeats = beatGrid.size
        val lowE = FloatArray(nBeats)
        val midE = FloatArray(nBeats)
        val highE = FloatArray(nBeats)
        val onset = FloatArray(nBeats)

        var globalMaxFeature = 0f

        for (i in 0 until nBeats) {
            val beatTime = beatGrid[i]
            val centerIndex = (beatTime * sampleRate).toInt()
            val start = (centerIndex - windowSamples / 2).coerceAtLeast(0)
            val end = (centerIndex + windowSamples / 2).coerceAtMost(pcmData.size)
            if (start >= end) {
                lowE[i] = 0f; midE[i] = 0f; highE[i] = 0f; onset[i] = 0f; continue
            }

            // RMS per band
            lowE[i] = rms(low, start, end)
            midE[i] = rms(mid, start, end)
            highE[i] = rms(high, start, end)

            // Onset proxy: sum of positive frame-energy differences inside window
            var prevFrameEnergy = 0f
            var onsetSum = 0f
            var frameStart = start
            while (frameStart < end) {
                val frameEnd = min(end, frameStart + frameSamples)
                val fe = rmsSumSimple(low, mid, high, frameStart, frameEnd)
                val diff = fe - prevFrameEnergy
                if (diff > 0f) onsetSum += diff
                prevFrameEnergy = fe
                frameStart += frameSamples
            }
            onset[i] = onsetSum

            // Feature magnitude to compute silence threshold later
            // We'll combine features with weights later; using a simple sum for scaling
            val featureMag = onset[i] + lowE[i] + midE[i] + highE[i]
            if (featureMag > globalMaxFeature) globalMaxFeature = featureMag
        }

        // --- 4. Combine features per beat with chosen weights ---
        // Emphasize onset and mid (snare/hits) over low (bass) and high.
        val wOnset = 1.0f
        val wLow = 0.35f
        val wMid = 0.7f
        val wHigh = 0.3f

        val features = FloatArray(nBeats)
        var maxFeature = 0f
        for (i in 0 until nBeats) {
            val f = wOnset * onset[i] + wLow * lowE[i] + wMid * midE[i] + wHigh * highE[i]
            features[i] = f
            if (f > maxFeature) maxFeature = f
        }

        if (maxFeature <= 0f) return BarDetectionResult(0, 0f)

        // --- 5. Silence gating to avoid quiet sections biasing the histogram ---
        val silenceThreshold = maxFeature * 0.12f

        // --- 6. Beat-phase histogram accumulation ---
        val buckets = FloatArray(timeSignature) { 0f }
        val counts = IntArray(timeSignature) { 0 }

        for (i in skipInitialBeats until nBeats) {
            val off = i % timeSignature
            val valFeature = features[i]
            if (valFeature < silenceThreshold) continue
            buckets[off] += valFeature
            counts[off]++
        }

        // If no buckets have counts (all quiet), return low confidence
        val anyCounts = counts.any { it > 0 }
        if (!anyCounts) return BarDetectionResult(0, 0f)

        // Mean per bucket to avoid long songs dominating by sheer count
        val means = FloatArray(timeSignature) { 0f }
        for (o in 0 until timeSignature) {
            if (counts[o] > 0) means[o] = buckets[o] / counts[o]
            else means[o] = 0f
        }

        // --- 7. Softmax-based confidence (stable numerics) ---
        val maxMean = means.maxOrNull() ?: 0f
        val exps = FloatArray(timeSignature)
        var sumExp = 0.0f
        for (o in 0 until timeSignature) {
            // shift by maxMean for numeric stability
            val e = exp((means[o] - maxMean).toDouble()).toFloat()
            exps[o] = e
            sumExp += e
        }

        // If sumExp is zero (shouldn't happen), fallback
        if (sumExp <= 0f) return BarDetectionResult(0, 0f)

        val probs = FloatArray(timeSignature)
        for (o in 0 until timeSignature) probs[o] = exps[o] / sumExp

        // Winner is the bucket with max mean
        var bestOffset = 0
        var bestMean = -Float.MAX_VALUE
        for (o in 0 until timeSignature) {
            if (means[o] > bestMean) { bestMean = means[o]; bestOffset = o }
        }

        val confidence = probs[bestOffset].coerceIn(0f, 1f)

        return BarDetectionResult(bestOffset, confidence)
    }

    // ---------------------- Helpers ----------------------

    private fun clamp(v: Float, minV: Float, maxV: Float) = when {
        v < minV -> minV
        v > maxV -> maxV
        else -> v
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val copy = values.copyOf()
        copy.sort()
        val m = copy.size / 2
        return if (copy.size % 2 == 1) copy[m] else (copy[m - 1] + copy[m]) / 2f
    }

    private fun rms(array: FloatArray, start: Int, end: Int): Float {
        var sum = 0.0
        for (i in start until end) {
            val s = array[i]
            sum += s * s
        }
        return sqrt((sum / max(1, end - start))).toFloat()
    }

    // simple combined-frame energy using band arrays
    private fun rmsSumSimple(low: FloatArray, mid: FloatArray, high: FloatArray, start: Int, end: Int): Float {
        var sum = 0.0
        for (i in start until end) {
            // squared sum across bands approximates spectral energy
            val v = low[i] + mid[i] + high[i]
            sum += v * v
        }
        return sqrt((sum / max(1, end - start))).toFloat()
    }

    // ---- Very small/fast single-pole filters ----
    // lowpass: y[n] = y[n-1] + alpha * (x[n] - y[n-1])
    private fun singlePoleLowpass(input: FloatArray, sampleRate: Int, cutoffHz: Float): FloatArray {
        val out = FloatArray(input.size)
        if (input.isEmpty()) return out
        val dt = 1.0f / sampleRate
        val rc = 1.0f / (2.0f * Math.PI.toFloat() * cutoffHz)
        val alpha = dt / (rc + dt)
        var y = 0f
        for (i in input.indices) {
            y = y + alpha * (input[i] - y)
            out[i] = y
        }
        return out
    }

    // highpass implemented as x - lowpassed(x) with a higher cutoff
    private fun singlePoleHighpass(input: FloatArray, sampleRate: Int, cutoffHz: Float): FloatArray {
        // Use the lowpass result and subtract from input to approximate a highpass
        val lp = singlePoleLowpass(input, sampleRate, cutoffHz)
        val out = FloatArray(input.size)
        for (i in input.indices) out[i] = input[i] - lp[i]
        return out
    }
}
