package com.dd3boh.outertune.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.dd3boh.outertune.transition.editor.BeatSample
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class BeatGridMarker(
    val beatIndex: Float,
    val isDownbeat: Boolean,
    val isGhost: Boolean = false
)

enum class BeatMarkerPosition {
    TOP, BOTTOM
}

/**
 * A horizontally draggable beat-domain waveform. Dragging scrolls it; on release it snaps the
 * nearest beat to the centre line and reports the new offset through [onOffsetChanged].
 *
 * Both [waveformData] and [beatMarkers] must be sorted by beat index: only the visible slice is
 * found (by binary search) and drawn, as one path.
 */
@Composable
fun WaveformView(
    waveformData: List<BeatSample>,
    beatMarkers: List<BeatGridMarker>,
    markerPosition: BeatMarkerPosition,
    modifier: Modifier = Modifier,
    pixelsPerBeat: Float = 48f,
    beatOffsetBeats: Float = 0f,
    initialOffset: Float,
    onOffsetChanged: (Float) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(initialOffset) }
    val snapAnimation = remember { Animatable(initialOffset) }
    val currentOnOffsetChanged by rememberUpdatedState(onOffsetChanged)
    val currentMarkers by rememberUpdatedState(beatMarkers)
    val waveformPath = remember { Path() }

    // Follow external changes (e.g. a restored transition) unless we're mid-snap.
    LaunchedEffect(initialOffset) {
        if (abs(offset - initialOffset) > 1f && !snapAnimation.isRunning) offset = initialOffset
    }

    Canvas(
        modifier = modifier.pointerInput(pixelsPerBeat, beatOffsetBeats) {
            detectHorizontalDragGestures(
                onDragStart = { scope.launch { snapAnimation.stop() } },
                onDragEnd = {
                    val viewCenter = size.width / 2f
                    val shiftPixels = beatOffsetBeats * pixelsPerBeat
                    val beatAtCenter = (viewCenter - offset - shiftPixels) / pixelsPerBeat
                    val nearestBeat = nearestMarkerBeat(currentMarkers, beatAtCenter)
                    val target = viewCenter - nearestBeat * pixelsPerBeat - shiftPixels

                    scope.launch {
                        snapAnimation.snapTo(offset)
                        snapAnimation.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) {
                            offset = value
                        }
                        currentOnOffsetChanged(target)
                    }
                }
            ) { change, dragAmount ->
                change.consume()
                offset += dragAmount
            }
        }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val maxAmplitude = height / 2f
        val totalShift = offset + beatOffsetBeats * pixelsPerBeat

        // Visible beat range, with a beat of margin either side
        val startVisibleBeat = -totalShift / pixelsPerBeat - 1f
        val endVisibleBeat = (width - totalShift) / pixelsPerBeat + 1f

        // Waveform: one vertical stroke per sample, all in a single path
        waveformPath.rewind()
        val first = lowerBound(waveformData.size) { waveformData[it].beatIndex >= startVisibleBeat }
        for (i in first until waveformData.size) {
            val sample = waveformData[i]
            if (sample.beatIndex > endVisibleBeat) break
            val x = sample.beatIndex * pixelsPerBeat + totalShift
            val amplitude = sample.amplitude.coerceIn(0f, 1f) * maxAmplitude
            waveformPath.moveTo(x, centerY - amplitude)
            waveformPath.lineTo(x, centerY + amplitude)
        }
        drawPath(waveformPath, Color.LightGray, style = Stroke(width = 2f))

        // Beat markers
        val firstMarker = lowerBound(beatMarkers.size) { beatMarkers[it].beatIndex >= startVisibleBeat }
        for (i in firstMarker until beatMarkers.size) {
            val marker = beatMarkers[i]
            if (marker.beatIndex > endVisibleBeat) break
            val x = marker.beatIndex * pixelsPerBeat + totalShift
            val color = when {
                marker.isDownbeat -> Color(0xFF4CAF50)
                marker.isGhost -> Color.DarkGray
                else -> Color.Gray.copy(alpha = 0.5f)
            }
            val strokeWidth = if (marker.isDownbeat) 4f else 2f
            val lineLength = if (marker.isDownbeat) 30f else 20f
            val (y0, y1) = if (markerPosition == BeatMarkerPosition.BOTTOM) height to height - lineLength else 0f to lineLength
            drawLine(color = color, start = Offset(x, y0), end = Offset(x, y1), strokeWidth = strokeWidth)
        }

        // Centre line / playhead reference
        drawLine(
            color = Color.Red,
            start = Offset(width / 2f, 0f),
            end = Offset(width / 2f, height),
            strokeWidth = 3f
        )
    }
}

private fun nearestMarkerBeat(markers: List<BeatGridMarker>, beat: Float): Float {
    if (markers.isEmpty()) return beat.roundToInt().toFloat()
    val i = lowerBound(markers.size) { markers[it].beatIndex >= beat }
    val after = markers.getOrNull(i)?.beatIndex
    val before = markers.getOrNull(i - 1)?.beatIndex
    return when {
        after == null -> before!!
        before == null -> after
        abs(after - beat) < abs(beat - before) -> after
        else -> before
    }
}

/** First index in [0, size) for which [isAtOrAfter] holds (it must be monotonic), else size. */
private inline fun lowerBound(size: Int, isAtOrAfter: (Int) -> Boolean): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (isAtOrAfter(mid)) hi = mid else lo = mid + 1
    }
    return lo
}
