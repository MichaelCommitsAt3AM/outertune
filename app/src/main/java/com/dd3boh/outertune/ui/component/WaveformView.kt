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

// 1. New Data Model for Markers
// Use this to define exactly where beats are and what they look like
data class BeatGridMarker(
    val beatIndex: Float,      // The X position (in beats)
    val isDownbeat: Boolean,   // TRUE = Green/Big (Major), FALSE = Gray/Small (Minor)
    val isGhost: Boolean = false // Optional: For beats estimated during silence
)

@Composable
fun WaveformView(
    waveformData: List<BeatSample>,
    beatMarkers: List<BeatGridMarker>, // 2. Updated signature
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

                        val exactBeatAtCenter = (viewCenter - currentScroll - shiftPixels) / pixelsPerBeat
                        val nearestBeatIndex = exactBeatAtCenter.roundToInt()
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
        val totalShift = currentOffset + beatOffsetPixels

        // Center reference line
        drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 2f
        )

        if (isBeatDomain && waveformData.isNotEmpty()) {
            // Calculate visible range for optimization
            val startVisibleBeat = (-totalShift / pixelsPerBeat) - 1f
            val endVisibleBeat = ((-totalShift + width) / pixelsPerBeat) + 1f

            // Draw waveform
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

            // ============================================
            // 3. Updated Drawing Logic
            // ============================================
            beatMarkers.forEach { marker ->
                // Check visibility using the marker's explicit index
                if (marker.beatIndex >= startVisibleBeat && marker.beatIndex <= endVisibleBeat) {
                    val x = (marker.beatIndex * pixelsPerBeat) + totalShift

                    // Use the property from the object, NOT modulo math
                    val isMajorBeat = marker.isDownbeat

                    val color = when {
                        isMajorBeat -> Color(0xFF4CAF50) // Green
                        marker.isGhost -> Color.DarkGray // Faint for ghost beats
                        else -> Color.Gray.copy(alpha = 0.5f)
                    }

                    val strokeWidth = if (isMajorBeat) 4f else 2f
                    val lineLength = if (isMajorBeat) 30f else 20f

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

        // Playhead indicator
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