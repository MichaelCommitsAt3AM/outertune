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
import com.dd3boh.outertune.transition.editor.BeatSample
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// NOTE: BeatGridMarker is now defined in EditorModels.kt or imported from there.
// If it was previously defined here, we should remove the definition and import it.
// Assuming it's shared, we use the one from the package.
// For now, I will assume BeatGridMarker is defined in `com.dd3boh.outertune.ui.component`
// as per your Phase 1 snippet (although Phase 1 put it in `EditorModels.kt` referencing `ui.component`).
// Let's define it here if it's strictly a UI helper, or import it.

// Re-using the class structure from your provided code, but ensuring imports match Phase 4.
data class BeatGridMarker(
    val beatIndex: Float,
    val isDownbeat: Boolean,
    val isGhost: Boolean = false
)

enum class BeatMarkerPosition {
    TOP, BOTTOM
}

@Composable
fun WaveformView(
    waveformData: List<BeatSample>,
    beatMarkers: List<BeatGridMarker>,
    markerPosition: BeatMarkerPosition,
    // songDurationSeconds is not strictly needed for drawing if we trust waveformData indices,
    // but useful for boundary checks if needed.
    modifier: Modifier = Modifier,
    pixelsPerBeat: Float = 48f,
    beatOffsetBeats: Float = 0f,
    initialOffset: Float,
    onOffsetChanged: (Float) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val horizontalOffset = remember { Animatable(initialOffset) }

    // Sync external changes to internal state (if initialOffset changes significantly)
    LaunchedEffect(initialOffset) {
        if (abs(horizontalOffset.value - initialOffset) > 1f && !horizontalOffset.isRunning) {
            horizontalOffset.snapTo(initialOffset)
        }
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    // Snap to nearest beat logic
                    val viewCenter = size.width.toFloat() / 2f
                    val currentScroll = horizontalOffset.value
                    val shiftPixels = beatOffsetBeats * pixelsPerBeat

                    // Calculate where the center currently is in "Beat Space"
                    // visual_center_x = (beat_index * ppb) + scroll + shift
                    // beat_index = (visual_center - scroll - shift) / ppb
                    val exactBeatAtCenter = (viewCenter - currentScroll - shiftPixels) / pixelsPerBeat
                    
                    // Snap to nearest beat marker
                    val nearestBeatIndex = if (beatMarkers.isNotEmpty()) {
                        beatMarkers.minByOrNull { abs(it.beatIndex - exactBeatAtCenter) }?.beatIndex
                            ?: exactBeatAtCenter.roundToInt().toFloat()
                    } else {
                        exactBeatAtCenter.roundToInt().toFloat()
                    }

                    // Calculate target scroll to put that beat exactly in center
                    val targetScroll = viewCenter - (nearestBeatIndex * pixelsPerBeat) - shiftPixels

                    scope.launch {
                        horizontalOffset.animateTo(
                            targetValue = targetScroll,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        )
                        onOffsetChanged(targetScroll)
                    }
                }
            ) { change, dragAmount ->
                change.consume()
                scope.launch {
                    val newOffset = horizontalOffset.value + dragAmount
                    horizontalOffset.snapTo(newOffset)
                    onOffsetChanged(newOffset)
                }
            }
        }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val maxAmplitude = height / 2f

        val currentOffset = horizontalOffset.value
        val totalShift = currentOffset + (beatOffsetBeats * pixelsPerBeat)

        // Center reference line
        drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 2f
        )

        if (waveformData.isNotEmpty()) {
            // Optimization: Only iterate visible samples
            // sample.x = (index * ppb) + totalShift
            // visible if 0 < x < width
            // index * ppb > -totalShift  -> index > -totalShift/ppb
            val startVisibleBeat = (-totalShift / pixelsPerBeat) - 2f
            val endVisibleBeat = ((-totalShift + width) / pixelsPerBeat) + 2f

            // Draw Waveform
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

            // Draw Markers
            beatMarkers.forEach { marker ->
                if (marker.beatIndex >= startVisibleBeat && marker.beatIndex <= endVisibleBeat) {
                    val x = (marker.beatIndex * pixelsPerBeat) + totalShift

                    val color = when {
                        marker.isDownbeat -> Color(0xFF4CAF50) // Green
                        marker.isGhost -> Color.DarkGray
                        else -> Color.Gray.copy(alpha = 0.5f)
                    }

                    val strokeWidth = if (marker.isDownbeat) 4f else 2f
                    val lineLength = if (marker.isDownbeat) 30f else 20f

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

        // Playhead indicator (Static Red Line)
        drawLine(
            color = Color.Red,
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 3f
        )
    }
}