package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.ui.component.BeatMarkerPosition
import com.dd3boh.outertune.ui.component.WaveformView
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.viewmodels.TransitionEditorViewModel

@Composable
fun TransitionEditorScreen(
    songAId: String,
    songBId: String,
    onCancel: () -> Unit = {},
    onSave: () -> Unit = {},
    viewModel: TransitionEditorViewModel = hiltViewModel()
) {

    // Phase 1: Trigger Data Load
    LaunchedEffect(songAId, songBId) {
        viewModel.loadData(songAId, songBId)
    }
    // Phase 2: Collect Data
    val track1 by viewModel.track1.collectAsState()
    val track2 by viewModel.track2.collectAsState()
    val waveformData1 by viewModel.waveformData1.collectAsState()
    val waveformData2 by viewModel.waveformData2.collectAsState()
    val beatMarkers1 by viewModel.beatGrid1.collectAsState()
    val beatMarkers2 by viewModel.beatGrid2.collectAsState()
    val zoomFactor1 by viewModel.zoomFactor1.collectAsState()
    val zoomFactor2 by viewModel.zoomFactor2.collectAsState()
    val barsCount by viewModel.barsCount.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var overlapMode by remember { mutableStateOf("Overlap") }
    var eqMode by remember { mutableStateOf("None") }
    var effectMode by remember { mutableStateOf("Low pass filt...") }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar
            TopBar(
                onCancel = onCancel,
                onSave = onSave
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Track 1 Info
            track1?.let { song ->
                TransitionTrackInfo(song = song, modifier = Modifier.padding(horizontal = 16.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Waveforms
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                Column {
                    // Track 1 Waveform - ADD zoomFactor parameter
                    WaveformView(
                        waveformData = waveformData1,
                        beatMarkers = beatMarkers1,
                        markerPosition = BeatMarkerPosition.BOTTOM,
                        songDurationSeconds = track1?.song?.duration?.toFloat(),
                        zoomFactor = zoomFactor1, // <-- ADD THIS
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )

                    // Track 2 Waveform - ADD zoomFactor parameter
                    WaveformView(
                        waveformData = waveformData2,
                        beatMarkers = beatMarkers2,
                        markerPosition = BeatMarkerPosition.TOP,
                        songDurationSeconds = track2?.song?.duration?.toFloat(),
                        zoomFactor = zoomFactor2, // <-- ADD THIS
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                }

                // Transition Overlay (Fixed center window)
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .background(
                            color = Color.Black.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }


            BarsDropdown(
                selectedBars = barsCount,
                onBarsSelected = { viewModel.setBarsCount(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )

            // Track 2 Info
            track2?.let { song ->
                TransitionTrackInfo(song = song, showDurationBadge = false, modifier = Modifier.padding(horizontal = 16.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Controls
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
        // FIX: Access .song.getThumbnailModel()
        AsyncImage(
            model = song.song.getThumbnailModel(),
            contentDescription = "Album Art",
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title, // Accessing from wrapper (overridden property) is fine
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = song.artists.joinToString { it.name }, // Accessing from wrapper is fine
                color = Color(0xFFAAAAAA),
                fontSize = 14.sp,
                maxLines = 1
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            // FIX: Access .song.bpm
            Text(
                text = song.song.bpm?.let { "${it.toInt()} bpm" } ?: "-- bpm",
                color = Color.White,
                fontSize = 12.sp
            )

            // FIX: Access .song.key
            song.song.key?.let { key ->
                Text(text = key, color = Color.Gray, fontSize = 12.sp)
            }

            if (showDurationBadge) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF3949AB)
                ) {
                    // FIX: Access .song.duration
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
