package com.dd3boh.outertune.transition.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A second-order IIR filter (RBJ "Audio EQ Cookbook" designs), Direct Form I, with independent
 * state per interleaved channel. Pure Kotlin.
 */
class Biquad(private val channels: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)

    /** Sets new coefficients, keeping the filter's state so a sweep doesn't click. */
    fun set(c: Coefficients) {
        b0 = c.b0; b1 = c.b1; b2 = c.b2; a1 = c.a1; a2 = c.a2
    }

    fun process(sample: Double, channel: Int): Double {
        val y = b0 * sample + b1 * x1[channel] + b2 * x2[channel] - a1 * y1[channel] - a2 * y2[channel]
        x2[channel] = x1[channel]; x1[channel] = sample
        y2[channel] = y1[channel]; y1[channel] = y
        return y
    }

    fun reset() {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
    }

    /** Normalised coefficients (a0 = 1). */
    data class Coefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    companion object {
        const val BUTTERWORTH_Q = 0.7071067811865476

        fun lowPass(sampleRate: Int, cutoffHz: Double, q: Double = BUTTERWORTH_Q): Coefficients {
            val (cosW, alpha) = prewarp(sampleRate, cutoffHz, q)
            val a0 = 1 + alpha
            return Coefficients(
                b0 = (1 - cosW) / 2 / a0,
                b1 = (1 - cosW) / a0,
                b2 = (1 - cosW) / 2 / a0,
                a1 = -2 * cosW / a0,
                a2 = (1 - alpha) / a0,
            )
        }

        fun highPass(sampleRate: Int, cutoffHz: Double, q: Double = BUTTERWORTH_Q): Coefficients {
            val (cosW, alpha) = prewarp(sampleRate, cutoffHz, q)
            val a0 = 1 + alpha
            return Coefficients(
                b0 = (1 + cosW) / 2 / a0,
                b1 = -(1 + cosW) / a0,
                b2 = (1 + cosW) / 2 / a0,
                a1 = -2 * cosW / a0,
                a2 = (1 - alpha) / a0,
            )
        }

        /** Low shelf with slope 1: [gainDb] below [cornerHz], flat above. */
        fun lowShelf(sampleRate: Int, cornerHz: Double, gainDb: Double): Coefficients {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * clampFrequency(sampleRate, cornerHz) / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / 2 * sqrt(2.0)
            val twoSqrtAAlpha = 2 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
            return Coefficients(
                b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha) / a0,
                b1 = 2 * a * ((a - 1) - (a + 1) * cosW) / a0,
                b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha) / a0,
                a1 = -2 * ((a - 1) + (a + 1) * cosW) / a0,
                a2 = ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha) / a0,
            )
        }

        private fun prewarp(sampleRate: Int, frequencyHz: Double, q: Double): Pair<Double, Double> {
            val w0 = 2 * PI * clampFrequency(sampleRate, frequencyHz) / sampleRate
            return cos(w0) to sin(w0) / (2 * q)
        }

        /** Keeps a design frequency safely below Nyquist. */
        private fun clampFrequency(sampleRate: Int, frequencyHz: Double) =
            frequencyHz.coerceIn(10.0, sampleRate * 0.45)
    }
}
