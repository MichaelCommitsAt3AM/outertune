package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun WaveformCanvas(
    amplitudes: List<Int>,
    modifier: Modifier = Modifier,
    barWidth: Dp = 4.dp,
    gapWidth: Dp = 2.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    beatIntervalPx: Float? = null, // Pixel distance between beats
    firstBeatOffsetPx: Float? = 0f
) {
    // Calculate total width based on number of bars
    val totalBars = amplitudes.size
    val totalWidthDp = (barWidth + gapWidth) * totalBars

    Canvas(
        modifier = modifier
            .width(totalWidthDp) // Force the canvas to be as wide as the data
            .fillMaxHeight()
    ) {
        val barW = barWidth.toPx()
        val gapW = gapWidth.toPx()
        val step = barW + gapW
        val center = size.height / 2

        // 1. Draw Audio Bars
        amplitudes.forEachIndexed { index, amp ->
            val x = index * step
            // Normalize amp (usually 0-100) to height
            val barHeight = (amp / 100f) * size.height * 0.8f

            drawLine(
                color = color,
                start = Offset(x, center - barHeight / 2),
                end = Offset(x, center + barHeight / 2),
                strokeWidth = barW,
                cap = StrokeCap.Round
            )
        }

        // 2. Draw Beat Grid (Vertical Lines)
        if (beatIntervalPx != null && beatIntervalPx > 0) {
            val gridColor = Color.White.copy(alpha = 0.3f)
            var currentX = firstBeatOffsetPx ?: 0f

            while (currentX < size.width) {
                drawLine(
                    color = gridColor,
                    start = Offset(currentX, 0f),
                    end = Offset(currentX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                currentX += beatIntervalPx
            }
        }
    }
}