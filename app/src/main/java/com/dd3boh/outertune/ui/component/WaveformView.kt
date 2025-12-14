// WaveformView.kt

package com.dd3boh.outertune.ui.component

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
import com.dd3boh.outertune.viewmodels.BeatSample
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun WaveformView(
    waveformData: List<BeatSample>,
    beatMarkers: List<Float>,
    markerPosition: BeatMarkerPosition,
    songDurationSeconds: Float?,
    modifier: Modifier = Modifier,
    isBeatDomain: Boolean = true,
    pixelsPerBeat: Float = 48f,
    beatOffsetBeats: Float = 0f,
    initialOffset: Float,
    onOffsetChanged: (Float) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val horizontalOffset = remember { Animatable(initialOffset) }

    // Use the pixelsPerBeat directly (no need to convert to int now)
    val effectivePxPerBeat = pixelsPerBeat

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (isBeatDomain) {
                        val viewCenter = size.width.toFloat() / 2f
                        val currentScroll = horizontalOffset.value

                        // Calculate offset for Track 2 (if any) in pixels
                        val shiftPixels = beatOffsetBeats * effectivePxPerBeat

                        // Find which beat index is closest to the center
                        val exactBeatAtCenter = (viewCenter - currentScroll - shiftPixels) / effectivePxPerBeat
                        val nearestBeatIndex = exactBeatAtCenter.roundToInt()

                        // Calculate the target scroll position to snap that beat to center
                        val targetScroll = viewCenter - (nearestBeatIndex * effectivePxPerBeat) - shiftPixels

                        scope.launch {
                            horizontalOffset.animateTo(
                                targetValue = targetScroll,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            )
                            onOffsetChanged(targetScroll)
                        }
                    }
                    // Legacy time-domain logic (Unchanged)
                    else if (songDurationSeconds != null && songDurationSeconds > 0f) {
                        val width = size.width.toFloat()
                        val totalWaveformWidth = width * 1f
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
            // Calculate shift for Track 2
            val beatOffsetPixels = beatOffsetBeats * effectivePxPerBeat


            // FIXED: Render waveform using beat positions
            if (waveformData.isNotEmpty()) {
                // Calculate visible beat range for optimization
                val visibleStartBeat = ((-currentOffset - beatOffsetPixels) / effectivePxPerBeat) - 1f
                val visibleEndBeat = visibleStartBeat + (width / effectivePxPerBeat) + 2f

                waveformData.forEach { sample ->
                    // Skip samples outside visible range
                    if (sample.beatIndex < visibleStartBeat || sample.beatIndex > visibleEndBeat) {
                        return@forEach
                    }

                    // FIXED: Calculate x position from beat index (not array index!)
                    val x = (sample.beatIndex * effectivePxPerBeat) + currentOffset + beatOffsetPixels

                    if (x < -10f || x > width + 10f) return@forEach

                    val normalizedAmp = sample.amplitude.coerceIn(0f, 1f)
                    val scaledAmplitude = normalizedAmp * maxAmplitude

                    drawLine(
                        color = Color.LightGray,
                        start = Offset(x, centerY - scaledAmplitude),
                        end = Offset(x, centerY + scaledAmplitude),
                        strokeWidth = 2f
                    )
                }
            }

            // Beat markers rendering
            if (beatMarkers.isNotEmpty()) {
                beatMarkers.forEach { beatIndexFloat ->

                    val x = (beatIndexFloat * effectivePxPerBeat) +
                            currentOffset +
                            beatOffsetPixels

                    val isMajorBeat = (beatIndexFloat % 4f == 0f)
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
            // TIME-DOMAIN / LEGACY RENDERER (Unchanged - but now expects List<BeatSample>)
            // Note: This branch won't work with BeatSample data structure
            // Keep for compatibility but it should use the old FloatArray if needed
            if (songDurationSeconds != null && songDurationSeconds > 0f) {
                // Legacy rendering would need the old FloatArray structure
                // For now, this is just a placeholder
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