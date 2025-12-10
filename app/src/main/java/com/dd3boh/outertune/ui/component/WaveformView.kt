// WaveformView.kt

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

@Composable
fun WaveformView(
    waveformData: FloatArray,
    beatMarkers: List<Float>,
    markerPosition: BeatMarkerPosition,
    songDurationSeconds: Float?,
    modifier: Modifier = Modifier,
    isBeatDomain: Boolean = true,
    pixelsPerBeat: Float = 48f,
    beatOffsetBeats: Float = 0f,
    initialOffset: Float = 0f,
    onOffsetChanged: (Float) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val horizontalOffset = remember { Animatable(initialOffset) }

    // --- FIX START ---
    // The waveform data was generated using pixelsPerBeat.toInt().
    // We must use that EXACT integer stride for drawing, or markers will drift from the waveform.
    val effectivePxPerBeat = remember(pixelsPerBeat, isBeatDomain) {
        if (isBeatDomain) pixelsPerBeat.toInt().toFloat() else pixelsPerBeat
    }
    // --- FIX END ---

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (isBeatDomain) {
                        val viewCenter = size.width.toFloat() / 2f
                        val currentScroll = horizontalOffset.value

                        // USE EFFECTIVE PIXELS HERE
                        val shiftPixels = beatOffsetBeats * effectivePxPerBeat

                        // Calculate exactly which beat is currently under the center line
                        val exactBeatAtCenter = (viewCenter - currentScroll - shiftPixels) / effectivePxPerBeat

                        val nearestBeatIndex = exactBeatAtCenter.roundToInt()

                        // USE EFFECTIVE PIXELS HERE
                        val targetScroll = viewCenter - (nearestBeatIndex * effectivePxPerBeat) - shiftPixels

                        scope.launch {
                            horizontalOffset.animateTo(
                                targetValue = targetScroll,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            )
                            onOffsetChanged(targetScroll)
                        }
                    }
                    // Legacy time-domain logic...
                    else if (!isBeatDomain && songDurationSeconds != null && songDurationSeconds > 0f) {
                        val width = size.width.toFloat()
                        val totalWaveformWidth = (width * 1f)
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

        // Center visual line (Anchor)
        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 2f
        )

        if (isBeatDomain) {
            // USE EFFECTIVE PIXELS for drawing
            val pxPerBeat = effectivePxPerBeat
            val beatOffsetPixels = beatOffsetBeats * pxPerBeat

            if (waveformData.isNotEmpty()) {
                val visibleStartIndex = (-currentOffset).roundToInt().coerceAtLeast(0)
                val visibleEndIndex = (visibleStartIndex + width.roundToInt()).coerceAtMost(waveformData.size - 1)

                for (i in visibleStartIndex..visibleEndIndex) {
                    val amplitude = waveformData[i]
                    // 'i' is the index in the array. Since the array was built with an integer stride,
                    // index 'i' corresponds exactly to pixel 'i' in the "beat domain" timeline.
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

            if (beatMarkers.isNotEmpty()) {
                beatMarkers.forEachIndexed { _, beatIndexFloat ->
                    // We simply multiply the index by the integer stride.
                    // Beat 10 * 48px = 480px. This matches the waveform index 480 exactly.
                    val x = (beatIndexFloat * pxPerBeat) + currentOffset + beatOffsetPixels

                    val isMajorBeat = (beatIndexFloat.roundToInt() % 4 == 0)
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
            // TIME-DOMAIN / LEGACY RENDERER (Unchanged)
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

        // Red Center Line (Always on top)
        drawLine(
            color = Color.Red,
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 4f
        )
    }
}

enum class BeatMarkerPosition {
    TOP, BOTTOM
}