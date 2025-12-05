package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.dd3boh.outertune.ui.component.WaveformCanvas
import com.dd3boh.outertune.viewmodels.MixEditorViewModel
import kotlin.math.roundToInt

@Composable
fun MixEditorDialog(
    songAId: String,
    songBId: String,
    onDismiss: () -> Unit,
    viewModel: MixEditorViewModel = hiltViewModel()
) {
    // Load data when dialog opens
    LaunchedEffect(Unit) {
        viewModel.loadData(songAId, songBId)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false) // Full width
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Text(
                    "Mix Editor",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (viewModel.songA == null || viewModel.songB == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    // --- DECK A (Top) ---
                    Text("Outgoing: ${viewModel.songA?.title}", style = MaterialTheme.typography.labelLarge)
                    DeckView(
                        waveform = viewModel.waveformA,
                        // Convert milliseconds to pixels (approximate mapping)
                        // In real app, you map 100 samples/sec -> px
                        offsetPx = -viewModel.exitPointMs * 0.1f,
                        onDrag = { delta ->
                            // Dragging left increases timestamp (moving forward in song)
                            viewModel.exitPointMs -= (delta * 10).toLong()
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // --- DECK B (Bottom) ---
                    Text("Incoming: ${viewModel.songB?.title}", style = MaterialTheme.typography.labelLarge)
                    DeckView(
                        waveform = viewModel.waveformB,
                        offsetPx = -viewModel.entryPointMs * 0.1f,
                        onDrag = { delta ->
                            viewModel.entryPointMs -= (delta * 10).toLong()
                            if (viewModel.entryPointMs < 0) viewModel.entryPointMs = 0
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // --- Duration Controls ---
                    Text("Transition Length (Beats)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        listOf(2, 4, 8, 16).forEach { beats ->
                            FilterChip(
                                selected = viewModel.durationBeats == beats,
                                onClick = { viewModel.durationBeats = beats },
                                label = { Text("$beats") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // --- Footer Buttons ---
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            viewModel.saveTransition()
                            onDismiss()
                        }) {
                            Text("Save Mix")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeckView(
    waveform: List<Int>,
    offsetPx: Float,
    onDrag: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // The Center Line (Playhead)
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.Red)
                .zIndex(10f)
        )

        // The Scrolling Waveform
        // We use offset logic to simulate scrolling
        if (waveform.isNotEmpty()) {
            WaveformCanvas(
                amplitudes = waveform,
                modifier = Modifier.offset(x = offsetPx.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text("No Waveform Data", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}