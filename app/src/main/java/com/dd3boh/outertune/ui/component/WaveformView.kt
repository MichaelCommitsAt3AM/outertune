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
    beatMarkers: List<Float>, // unused now, generated locally
    markerPosition: BeatMarkerPosition,
    songDurationSeconds: Float?,
    modifier: Modifier = Modifier,
    isBeatDomain: Boolean = true,
    pixelsPerBeat: Float = 48f,
    beatOffsetBeats: Float = 0f,
    initialOffset: Float, // This now receives PIXELS
    onOffsetChanged: (Float) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val horizontalOffset = remember { Animatable(initialOffset) }

    // Ensure we start at the passed initial offset
    LaunchedEffect(initialOffset) {
        if (abs(horizontalOffset.value - initialOffset) > 1f && !horizontalOffset.isRunning) {
            horizontalOffset.snapTo(initialOffset)
        }
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (isBeatDomain) {
                        val viewCenter = size.width.toFloat() / 2f
                        val currentScroll = horizontalOffset.value
                        val shiftPixels = beatOffsetBeats * pixelsPerBeat

                        // Snap to nearest beat
                        // Note: beatIndex increases as we scroll left (negative offset)
                        val exactBeatAtCenter = (viewCenter - currentScroll - shiftPixels) / pixelsPerBeat
                        val nearestBeatIndex = exactBeatAtCenter.roundToInt()

                        // Recalculate offset to center that beat
                        val targetScroll = viewCenter - (nearestBeatIndex * pixelsPerBeat) - shiftPixels

                        scope.launch {
                            horizontalOffset.animateTo(
                                targetValue = targetScroll,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            )
                            onOffsetChanged(targetScroll)
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
        val beatOffsetPixels = beatOffsetBeats * pixelsPerBeat

        // 1. Calculate Visible Beat Range (Optimization)
        // We only draw beats that are actually on screen
        val totalShift = currentOffset + beatOffsetPixels
        // Inverse formula: x = beat * px + shift  ->  beat = (x - shift) / px
        val startVisibleBeat = (-totalShift / pixelsPerBeat) - 1f
        val endVisibleBeat = ((-totalShift + width) / pixelsPerBeat) + 1f

        // Center line (Anchor)
        drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 2f
        )

        if (isBeatDomain && waveformData.isNotEmpty()) {

            // 2. Waveform Drawing
            // Simply iterate. Since we filtered logic inside, this is fast enough for <10k points.
            // For huge arrays, you'd use binary search to find start index.
            waveformData.forEach { sample ->
                if (sample.beatIndex >= startVisibleBeat && sample.beatIndex <= endVisibleBeat) {
                    val x = (sample.beatIndex * pixelsPerBeat) + totalShift

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

            // 3. Grid Markers (Generated strictly from Integers)
            // This guarantees markers are mathematically perfect relative to the beats
            val firstMarker = startVisibleBeat.toInt().coerceAtLeast(0)
            val lastMarker = endVisibleBeat.toInt()

            for (i in firstMarker..lastMarker) {
                val x = (i * pixelsPerBeat) + totalShift
                val isMajorBeat = (i % 4 == 0)

                val color = if (isMajorBeat) Color(0xFF4CAF50) else Color.Gray.copy(alpha=0.5f)
                val strokeWidth = if (isMajorBeat) 4f else 2f
                // Full height for major lines aids alignment visual
                val lineLength = if (isMajorBeat) height else 30f

                // Draw Marker Line
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

        // Red Center Indicator (Playhead)
        drawLine(
            color = Color.Red,
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 3f
        )
    }
}

enum class BeatMarkerPosition {
    TOP, BOTTOM
}