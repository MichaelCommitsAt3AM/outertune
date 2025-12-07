package com.dd3boh.outertune.ui.component

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
    modifier: Modifier = Modifier
) {
    var horizontalOffset by remember { mutableStateOf(0f) }

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

        // Draw grid
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

        // Draw waveform
        if (waveformData.isNotEmpty()) {
            val pixelsPerSample = width / waveformData.size

            waveformData.forEachIndexed { index, amplitude ->
                val x = (index * pixelsPerSample) + horizontalOffset
                if (x in 0f..width) {
                    val scaledAmplitude = amplitude.coerceIn(-1f, 1f)
                    val top = centerY - (scaledAmplitude * maxAmplitude)
                    val bottom = centerY + (scaledAmplitude * maxAmplitude)

                    drawLine(
                        color = Color.LightGray,
                        start = Offset(x, top),
                        end = Offset(x, bottom),
                        strokeWidth = 1f
                    )
                }
            }
        }

        // Draw beat markers
        beatMarkers.forEach { position ->
            val x = position + horizontalOffset
            if (x in 0f..width) {
                val y = if (markerPosition == BeatMarkerPosition.BOTTOM) {
                    height - 30f
                } else {
                    30f
                }

                drawCircle(
                    color = Color.Green,
                    radius = 6f,
                    center = Offset(x, y)
                )
            }
        }
    }
}

enum class BeatMarkerPosition {
    TOP, BOTTOM
}
