package com.dd3boh.outertune.ui.component

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * WaveformView - supports two modes:
 *  - Beat-domain mode (isBeatDomain = true): waveformData is a beat-domain array
 *    where each element corresponds to roughly one visual pixel. Beats are uniformly spaced:
 *      pixelsPerBeat controls spacing.
 *    beatMarkers should contain the beat indices (0,1,2,...) as Floats and
 *    beatOffsetBeats shifts the deck by a number of beats (positive = move right).
 *
 *  - Time-domain mode (isBeatDomain = false): legacy behavior (kept for fallback).
 */
@Composable
fun WaveformView(
    waveformData: FloatArray,
    beatMarkers: List<Float>,          // in beat-domain mode: beat indices (0,1,2,...)
    markerPosition: BeatMarkerPosition,
    songDurationSeconds: Float?,       // in time-domain mode: track duration; in beat-domain mode can be null or ignored
    modifier: Modifier = Modifier,
    isBeatDomain: Boolean = true,      // <-- Use beat-domain rendering by default in new system
    pixelsPerBeat: Float = 48f,        // only used in beat-domain mode
    beatOffsetBeats: Float = 0f,       // shift deck by this many beats (can be fractional)
    initialOffset: Float = 0f,
    onOffsetChanged: (Float) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val horizontalOffset = remember { Animatable(initialOffset) }

    // On some zoom resets we snap offset to center
    LaunchedEffect(pixelsPerBeat) {
        // optional: reset offset on big changes
        // horizontalOffset.snapTo(0f)
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    // snapping behavior: in beat-domain mode we snap to nearest beat (if available)
                    if (isBeatDomain && beatMarkers.isNotEmpty()) {
                        val width = size.width.toFloat()
                        val totalWidth = if (waveformData.isNotEmpty()) waveformData.size.toFloat() else width
                        val currentOffset = horizontalOffset.value
                        val centerPixelInWaveform = -currentOffset + (width / 2f)

                        // center beat index in beat-domain coordinates:
                        val centerBeatIndex = centerPixelInWaveform / pixelsPerBeat

                        // find closest beat index from beatMarkers (they are indices)
                        val closestBeatIndex = beatMarkers.minByOrNull { abs(it - centerBeatIndex) }
                        if (closestBeatIndex != null) {
                            // compute target center pixel for that beat
                            val beatOffsetPixels = beatOffsetBeats * pixelsPerBeat
                            val newCenterPixel = (closestBeatIndex * pixelsPerBeat) + beatOffsetPixels
                            val targetOffset = -(newCenterPixel - (width / 2f))
                            scope.launch {
                                horizontalOffset.animateTo(
                                    targetValue = targetOffset,
                                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                                )
                                onOffsetChanged(targetOffset)
                            }
                        }
                    } else if (!isBeatDomain && songDurationSeconds != null && songDurationSeconds > 0f && beatMarkers.isNotEmpty()) {
                        // legacy snapping: time-domain
                        val width = size.width.toFloat()
                        val totalWaveformWidth = (width * 1f) // zoom / speed handled externally in legacy mode
                        val currentOffset = horizontalOffset.value
                        val centerPixelInWaveform = -currentOffset + (width / 2f)
                        val centerTimeSeconds = (centerPixelInWaveform / totalWaveformWidth) * songDurationSeconds
                        val closestBeat = beatMarkers.minByOrNull { abs(it - centerTimeSeconds) }
                        if (closestBeat != null) {
                            val diffSeconds = abs(closestBeat - centerTimeSeconds)
                            if (diffSeconds < 0.5f) {
                                val newCenterPixel = (closestBeat / songDurationSeconds) * totalWaveformWidth
                                val targetOffset = -(newCenterPixel - (width / 2f))
                                scope.launch {
                                    horizontalOffset.animateTo(
                                        targetValue = targetOffset,
                                        animationSpec = spring(stiffness = Spring.StiffnessLow)
                                    )
                                    onOffsetChanged(targetOffset)
                                }
                            }
                        }
                    }
                }
            ) { change, dragAmount ->
                change.consume()
                scope.launch {
                    horizontalOffset.snapTo(horizontalOffset.value + dragAmount)
                    onOffsetChanged(horizontalOffset.value)
                }
            }
        }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val maxAmplitude = height / 2f
        val currentOffset = horizontalOffset.value

        // Center visual line
        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 2f
        )

        if (isBeatDomain) {
            // ---------- BEAT-DOMAIN RENDERER ----------
            val pxPerBeat = pixelsPerBeat
            val beatOffsetPixels = beatOffsetBeats * pxPerBeat

            // waveformData is already resampled per-pixel in beat-domain.
            // each element represents one visual pixel (approximately).
            if (waveformData.isNotEmpty()) {
                // visible window in indices (waveformData index ~ visual pixel)
                val visibleStartIndex = (-currentOffset).roundToInt().coerceAtLeast(0)
                val visibleEndIndex = (visibleStartIndex + width.roundToInt()).coerceAtMost(waveformData.size - 1)

                for (i in visibleStartIndex..visibleEndIndex) {
                    val amplitude = waveformData[i]
                    val x = i.toFloat() + currentOffset + beatOffsetPixels

                    if (x < -10f || x > width + 10f) continue

                    val normalizedAmp = amplitude.coerceIn(0f, 1f)
                    val scaledAmplitude = normalizedAmp * maxAmplitude

                    drawLine(
                        color = Color.LightGray,
                        start = Offset(x, centerY - scaledAmplitude),
                        end = Offset(x, centerY + scaledAmplitude),
                        strokeWidth = 2f
                    )
                }
            }

            // draw beat markers at integer beat indices:
            if (beatMarkers.isNotEmpty()) {
                beatMarkers.forEachIndexed { index, beatIndexFloat ->
                    // beatIndexFloat should be e.g. 0f, 1f, 2f... (we accept floats but treat them as indices)
                    val beatIndex = beatIndexFloat
                    val x = (beatIndex * pxPerBeat) + currentOffset + beatOffsetPixels

                    val isMajorBeat = (beatIndex.roundToInt() % 4 == 0)
                    val color = if (isMajorBeat) Color(0xFF4CAF50) else Color.Gray
                    val strokeWidth = if (isMajorBeat) 6f else 3f
                    val lineLength = if (isMajorBeat) 50f else 30f

                    if (x in -50f..(width + 50f)) {
                        if (markerPosition == BeatMarkerPosition.BOTTOM) {
                            drawLine(
                                color = color,
                                start = Offset(x, height),
                                end = Offset(x, height - lineLength),
                                strokeWidth = strokeWidth
                            )
                        } else {
                            drawLine(
                                color = color,
                                start = Offset(x, 0f),
                                end = Offset(x, lineLength),
                                strokeWidth = strokeWidth
                            )
                        }
                    }
                }
            }

        } else {
            // ---------- TIME-DOMAIN / LEGACY RENDERER ----------
            // Keep previous behaviour (time -> pixel mapping)
            if (waveformData.isNotEmpty() && songDurationSeconds != null && songDurationSeconds > 0f) {
                val pixelsPerSample = (width * 1f) / waveformData.size
                val visibleStartPixel = -currentOffset
                val visibleEndPixel = visibleStartPixel + width

                val startIndex = (visibleStartPixel / pixelsPerSample).toInt().coerceAtLeast(0)
                val endIndex = (visibleEndPixel / pixelsPerSample).toInt().coerceAtMost(waveformData.size - 1)

                for (index in startIndex..endIndex) {
                    val amplitude = waveformData[index]
                    val x = (index * pixelsPerSample) + currentOffset

                    if (x < -10f || x > width + 10f) continue

                    val normalizedAmp = amplitude.coerceIn(0f, 1f)
                    val scaledAmplitude = normalizedAmp * maxAmplitude

                    drawLine(
                        color = Color.LightGray,
                        start = Offset(x, centerY - scaledAmplitude),
                        end = Offset(x, centerY + scaledAmplitude),
                        strokeWidth = 2f
                    )
                }
            }

            if (beatMarkers.isNotEmpty() && songDurationSeconds != null && songDurationSeconds > 0f) {
                val totalWaveformWidth = width
                beatMarkers.forEachIndexed { index, beatTimeSeconds ->
                    val normalizedPosition = beatTimeSeconds / songDurationSeconds
                    val x = (normalizedPosition * totalWaveformWidth) + currentOffset

                    if (x in -50f..(width + 50f)) {
                        val isMajorBeat = index % 4 == 0
                        val color = if (isMajorBeat) Color(0xFF4CAF50) else Color.Gray
                        val strokeWidth = if (isMajorBeat) 6f else 3f
                        val lineLength = if (isMajorBeat) 50f else 30f

                        if (markerPosition == BeatMarkerPosition.BOTTOM) {
                            drawLine(
                                color = color,
                                start = Offset(x, height),
                                end = Offset(x, height - lineLength),
                                strokeWidth = strokeWidth
                            )
                        } else {
                            drawLine(
                                color = color,
                                start = Offset(x, 0f),
                                end = Offset(x, lineLength),
                                strokeWidth = strokeWidth
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class BeatMarkerPosition {
    TOP, BOTTOM
}
