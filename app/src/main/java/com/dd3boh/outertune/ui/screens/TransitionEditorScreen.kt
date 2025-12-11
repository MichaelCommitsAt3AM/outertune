package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.Log
import coil3.compose.AsyncImage
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.ui.component.BeatMarkerPosition
import com.dd3boh.outertune.ui.component.WaveformView
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.viewmodels.TransitionEditorViewModel
import com.dd3boh.outertune.viewmodels.BeatSample


@Composable
fun TransitionEditorScreen(
    songAId: String,
    songBId: String,
    onCancel: () -> Unit = {},
    onSave: () -> Unit = {},
    viewModel: TransitionEditorViewModel = hiltViewModel()
) {
    // Load tracks when IDs change
    LaunchedEffect(songAId, songBId) {
        viewModel.loadData(songAId, songBId)
    }

    // Collect state from ViewModel
    val track1 by viewModel.track1.collectAsState()
    val track2 by viewModel.track2.collectAsState()

    // FIX: Use beat domain data
    val waveformData1 by viewModel.waveformBeatDomain1.collectAsState()
    val waveformData2 by viewModel.waveformBeatDomain2.collectAsState()

    // FIX: Use generic beat indices (0, 1, 2...) instead of raw time grids
    val beatIndices by viewModel.beatGridIndices.collectAsState(initial = emptyList())

    // FIX: Use pixelsPerBeat instead of zoomFactor
    val pixelsPerBeat by viewModel.pixelsPerBeatBase.collectAsState()

    // FIX: Get offset for Track 2
    val beatOffsetTrack2 by viewModel.beatOffsetForTrack2.collectAsState()

    val barsCount by viewModel.barsCount.collectAsState()
    val transitionDuration by viewModel.transitionDurationSeconds.collectAsState()
    val transitionWidthFraction by viewModel.transitionWidthFraction.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var overlapMode by remember { mutableStateOf("Overlap") }
    var eqMode by remember { mutableStateOf("None") }
    var effectMode by remember { mutableStateOf("Low pass filt...") }

    LaunchedEffect(waveformData1, waveformData2, track1, track2) {
        Log.d("TransitionEditor", "Waveform1 size: ${waveformData1.size}")
        Log.d("TransitionEditor", "Waveform2 size: ${waveformData2.size}")
        Log.d("TransitionEditor", "Track1 duration: ${track1?.song?.duration}")
        Log.d("TransitionEditor", "Track2 duration: ${track2?.song?.duration}")
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()

        // ------------------------
        // COMPUTE ZOOM PROPERLY
        // ------------------------
        val totalBeats = (barsCount * 4).toFloat()
        val transitionZoneWidthPx = screenWidthPx * transitionWidthFraction

        // Core formula for correct zooming:
        val computedPixelsPerBeat =
            if (totalBeats > 0) transitionZoneWidthPx / totalBeats else 1f

        // Debug print (optional)
        Log.d("Zoom", "bars=$barsCount beats=$totalBeats ppb=$computedPixelsPerBeat width=$transitionZoneWidthPx")

        // When screen width (layout) or barsCount changes, update the VM
        LaunchedEffect(screenWidthPx, barsCount) {
            viewModel.setScreenWidth(screenWidthPx)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(onCancel, onSave)

            Spacer(modifier = Modifier.height(24.dp))

            track1?.let { TransitionTrackInfo(it, modifier = Modifier.padding(horizontal = 16.dp)) }

            Spacer(modifier = Modifier.height(24.dp))

            WaveformsSection(
                track1 = track1,
                track2 = track2,
                waveformData1 = waveformData1,
                waveformData2 = waveformData2,
                beatMarkers = beatIndices,
                beatOffsetTrack2 = beatOffsetTrack2,
                pixelsPerBeat = computedPixelsPerBeat,
                transitionWidthFraction = transitionWidthFraction,
                transitionDuration = transitionDuration,
                barsCount = barsCount
            )

            BarsDropdown(
                selectedBars = barsCount,
                onBarsSelected = { viewModel.setBarsCount(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )

            track2?.let { TransitionTrackInfo(it, showDurationBadge = false, modifier = Modifier.padding(horizontal = 16.dp)) }

            Spacer(modifier = Modifier.weight(1f))

            ControlPanel(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                overlapMode = overlapMode,
                onOverlapModeChanged = { overlapMode = it },
                eqMode = eqMode,
                onEqModeChanged = { eqMode = it },
                effectMode = effectMode,
                onEffectModeChanged = { effectMode = it },
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
fun WaveformsSection(
    track1: Song?,
    track2: Song?,
    waveformData1: List<BeatSample>,
    waveformData2: List<BeatSample>,
    beatMarkers: List<Float>,
    beatOffsetTrack2: Float,
    pixelsPerBeat: Float,
    transitionWidthFraction: Float,
    transitionDuration: Float,
    barsCount: Int,
    initialOffset: Float = 0f,

) {
    Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
        Column {
            // Track 1 waveform
            key(barsCount, pixelsPerBeat) {
                WaveformView(
                    waveformData = waveformData1,
                    beatMarkers = beatMarkers,
                    markerPosition = BeatMarkerPosition.BOTTOM,
                    isBeatDomain = true,
                    pixelsPerBeat = pixelsPerBeat,
                    beatOffsetBeats = 0f,
                    initialOffset = 0f,
                    songDurationSeconds = track1?.song?.duration?.toFloat() ?: 1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
            }
            // Track 2 waveform
            key(barsCount, pixelsPerBeat, beatOffsetTrack2) {
                WaveformView(
                    waveformData = waveformData2,
                    beatMarkers = beatMarkers,
                    markerPosition = BeatMarkerPosition.TOP,
                    isBeatDomain = true,
                    pixelsPerBeat = pixelsPerBeat,
                    beatOffsetBeats = beatOffsetTrack2,
                    initialOffset = 0f,
                    songDurationSeconds = track2?.song?.duration?.toFloat() ?: 1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )

            }
        }

        // Transition overlay
        BoxWithConstraints(modifier = Modifier.fillMaxSize().align(Alignment.Center)) {
            val screenWidth = maxWidth
            val transitionWidth = screenWidth * transitionWidthFraction

            Box(
                modifier = Modifier
                    .width(transitionWidth)
                    .fillMaxHeight()
                    .align(Alignment.Center)
            ) {
                // Background + border
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF4CAF50).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(2.dp, Color(0xFF4CAF50).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                )

                // Labels
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF4CAF50).copy(alpha = 0.9f)
                ) {
                    Text("Transition Zone", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }

                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF2C2C2C).copy(alpha = 0.95f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("$barsCount bars", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("%.1fs".format(transitionDuration), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                // Edge markers
                Box(modifier = Modifier.width(2.dp).fillMaxHeight().align(Alignment.CenterStart).background(Color(0xFF4CAF50).copy(alpha = 0.8f)))
                Box(modifier = Modifier.width(2.dp).fillMaxHeight().align(Alignment.CenterEnd).background(Color(0xFF4CAF50).copy(alpha = 0.8f)))
            }
        }
    }
}

@Composable
fun TopBar(
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) {
            Text(
                text = "Cancel",
                color = Color.White,
                fontSize = 16.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Edit transition",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))
        }

        TextButton(onClick = onSave) {
            Text(
                text = "Save",
                color = Color(0xFF4CAF50),
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun TransitionTrackInfo(
    song: Song,
    showDurationBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.song.getThumbnailModel(),
            contentDescription = "Album Art",
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = song.artists.joinToString { it.name },
                color = Color(0xFFAAAAAA),
                fontSize = 14.sp,
                maxLines = 1
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = song.song.bpm?.let { "${it.toInt()} bpm" } ?: "-- bpm",
                color = Color.White,
                fontSize = 12.sp
            )

            song.song.key?.let { key ->
                Text(text = key, color = Color.Gray, fontSize = 12.sp)
            }

            if (showDurationBadge) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF3949AB)
                ) {
                    Text(
                        text = makeTimeString(song.song.duration * 1000L),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarsDropdown(
    selectedBars: Int,
    onBarsSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val barsOptions = listOf(2, 4, 8, 16, 32)
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2C2C2C),
            onClick = { expanded = !expanded }
        ) {
            Text(
                text = "$selectedBars bars ⌄",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            barsOptions.forEach { bars ->
                DropdownMenuItem(
                    text = { Text("$bars bars") },
                    onClick = {
                        onBarsSelected(bars)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPanel(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    overlapMode: String,
    onOverlapModeChanged: (String) -> Unit,
    eqMode: String,
    onEqModeChanged: (String) -> Unit,
    effectMode: String,
    onEffectModeChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Main Control Panel
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C1C1C),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TabButton(
                text = "Volume",
                selected = selectedTab == 0,
                onClick = {
                    onTabSelected(0)
                    showBottomSheet = true
                },
                modifier = Modifier.weight(1f)
            )

            TabButton(
                text = "EQ",
                selected = selectedTab == 1,
                onClick = {
                    onTabSelected(1)
                    showBottomSheet = true
                },
                modifier = Modifier.weight(1f)
            )

            TabButton(
                text = "Effect",
                selected = selectedTab == 2,
                onClick = {
                    onTabSelected(2)
                    showBottomSheet = true
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Modal Bottom Sheet with drag-to-dismiss
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                // Custom drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(Color(0xFF4A4A4A), RoundedCornerShape(2.dp))
                    )
                }
            }
        ) {
            BottomSheetContent(
                selectedTab = selectedTab,
                overlapMode = overlapMode,
                onOverlapModeChanged = {
                    onOverlapModeChanged(it)
                    showBottomSheet = false
                },
                eqMode = eqMode,
                onEqModeChanged = {
                    onEqModeChanged(it)
                    showBottomSheet = false
                },
                effectMode = effectMode,
                onEffectModeChanged = {
                    onEffectModeChanged(it)
                    showBottomSheet = false
                }
            )
        }
    }
}

@Composable
fun BottomSheetContent(
    selectedTab: Int,
    overlapMode: String,
    onOverlapModeChanged: (String) -> Unit,
    eqMode: String,
    onEqModeChanged: (String) -> Unit,
    effectMode: String,
    onEffectModeChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) {
        // Title based on selected tab
        val title = when (selectedTab) {
            0 -> "Overlap Mode"
            1 -> "EQ Mode"
            else -> "Effect Mode"
        }

        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Options based on tab
        when (selectedTab) {
            0 -> {
                listOf("Overlap", "Crossfade", "Cut").forEach { option ->
                    ModernOptionItem(
                        text = option,
                        selected = option == overlapMode,
                        onClick = { onOverlapModeChanged(option) },
                        icon = when (option) {
                            "Overlap" -> "○○"
                            "Crossfade" -> "◐◑"
                            else -> "●○"
                        }
                    )
                }
            }
            1 -> {
                listOf("None", "Centre Bass swap", "End Bass Swap", "Onset Bass Swap").forEach { option ->
                    ModernOptionItem(
                        text = option,
                        selected = option == eqMode,
                        onClick = { onEqModeChanged(option) },
                        icon = when (option) {
                            "None" -> "─"
                            "Low pass" -> "⌄"
                            "High pass" -> "⌃"
                            else -> "◇"
                        }
                    )
                }
            }
            2 -> {
                listOf("None", "Low pass in", "Low Pass out", "High Pass in", "High Pass Out").forEach { option ->
                    ModernOptionItem(
                        text = option,
                        selected = option == effectMode,
                        onClick = { onEffectModeChanged(option) },
                        icon = when (option) {
                            "None" -> "─"
                            "Low pass in" -> "x"
                            "Low Pass out" -> "y"
                            "High Pass in" -> "z"
                            "High Pass Out" -> "d"
                            else -> "∿"
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun ModernOptionItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: String
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color(0xFF2A2A2A) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (selected) Color(0xFF4CAF50).copy(alpha = 0.2f)
                            else Color(0xFF2A2A2A),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = icon,
                        color = if (selected) Color(0xFF4CAF50) else Color(0xFF8A8A8A),
                        fontSize = 18.sp
                    )
                }

                // Option text
                Text(
                    text = text,
                    color = if (selected) Color.White else Color(0xFFB0B0B0),
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                )
            }

            // Selection indicator
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(0xFF4CAF50), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun OptionHeader(text: String) {
    Text(
        text = text,
        color = Color.Gray,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

@Composable
fun OptionItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = if (selected) Color(0xFF4CAF50) else Color.White,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )

            if (selected) {
                Icon(
                    painter = painterResource(android.R.drawable.checkbox_on_background),
                    contentDescription = "Selected",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = Color.Transparent
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = text,
                color = if (selected) Color(0xFF4CAF50) else Color.White,
                fontSize = 14.sp
            )
            if (selected) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .background(Color(0xFF4CAF50), RoundedCornerShape(1.dp))
                )
            }
        }
    }
}
