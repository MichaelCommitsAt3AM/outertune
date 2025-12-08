package com.dd3boh.outertune.ui.component

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun WaveformView(
    waveformData: FloatArray,
    beatMarkers: List<Float>,
    markerPosition: BeatMarkerPosition,
    songDurationSeconds: Float?,
    modifier: Modifier = Modifier,
    zoomFactor: Float = 1f,
    initialOffset: Float = 0f
) {
    var horizontalOffset by remember { mutableStateOf(initialOffset) }

    // Center the view when zoom changes
    LaunchedEffect(zoomFactor) {
        if (zoomFactor > 1f) {
            // Center the zoomed waveform
            // When zoomed in, show the beginning of the song centered
            horizontalOffset = 0f
        } else {
            horizontalOffset = 0f
        }
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                horizontalOffset += dragAmount.x
            }
        }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val maxAmplitude = height / 2f

        // Debug logging
        Log.d("WaveformView", "Canvas: width=$width, zoom=$zoomFactor, offset=$horizontalOffset, waveform=${waveformData.size}, beats=${beatMarkers.size}")

        // Draw grid (4 bars)
        val barWidth = width / 4f
        for (i in 0..4) {
            val x = i * barWidth
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1f
            )
        }

        // Draw waveform with zoom (FIXED)
        if (waveformData.isNotEmpty() && songDurationSeconds != null && songDurationSeconds > 0) {
            // Calculate how much width each sample should take
            val totalWaveformWidth = width * zoomFactor
            val pixelsPerSample = totalWaveformWidth / waveformData.size

            // Draw only visible samples for performance
            val startIndex = ((-horizontalOffset) / pixelsPerSample).toInt().coerceAtLeast(0)
            val endIndex = ((width - horizontalOffset) / pixelsPerSample).toInt().coerceAtMost(waveformData.size - 1)

            Log.d("WaveformView", "Drawing samples $startIndex to $endIndex (of ${waveformData.size}), pixelsPerSample=$pixelsPerSample")

            for (index in startIndex..endIndex) {
                val amplitude = waveformData[index]
                val x = (index * pixelsPerSample) + horizontalOffset

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

        // Draw beat markers as dots (FIXED)
        if (beatMarkers.isNotEmpty() && songDurationSeconds != null && songDurationSeconds > 0) {
            val totalWaveformWidth = width * zoomFactor

            beatMarkers.forEachIndexed { index, beatTimeSeconds ->
                // Convert beat time to position
                val normalizedPosition = beatTimeSeconds / songDurationSeconds
                val x = (normalizedPosition * totalWaveformWidth) + horizontalOffset

                // Only draw if visible
                if (x in -50f..(width + 50f)) {
                    val isMajorBeat = index % 4 == 0

                    when (markerPosition) {
                        BeatMarkerPosition.BOTTOM -> {
                            drawCircle(
                                color = if (isMajorBeat) Color(0xFF4CAF50) else Color(0xFF81C784),
                                radius = if (isMajorBeat) 8f else 5f,
                                center = Offset(x, height - 15f)
                            )
                        }
                        BeatMarkerPosition.TOP -> {
                            drawCircle(
                                color = if (isMajorBeat) Color(0xFF4CAF50) else Color(0xFF81C784),
                                radius = if (isMajorBeat) 8f else 5f,
                                center = Offset(x, 15f)
                            )
                        }
                    }

                    // Draw vertical line for major beats
                    if (isMajorBeat) {
                        drawLine(
                            color = Color(0xFF4CAF50).copy(alpha = 0.3f),
                            start = Offset(x, 0f),
                            end = Offset(x, height),
                            strokeWidth = 2f
                        )
                    }
                }
            }
        }
    }
}

enum class BeatMarkerPosition {
    TOP, BOTTOM
}
