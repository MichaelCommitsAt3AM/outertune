package com.dd3boh.outertune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.Log
import coil3.compose.AsyncImage
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.transition.editor.BeatSample
import com.dd3boh.outertune.ui.component.BeatMarkerPosition
import com.dd3boh.outertune.ui.component.WaveformView
import com.dd3boh.outertune.utils.TransitionMixer
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.viewmodels.TransitionEditorViewModel
import com.dd3boh.outertune.ui.component.BeatGridMarker

@Composable
fun TransitionEditorScreen(
    songAId: String,
    songBId: String,
    onCancel: () -> Unit = {},
    onSave: () -> Unit = {},
    viewModel: TransitionEditorViewModel = hiltViewModel()
) {
    // --- Load Data ---
    LaunchedEffect(songAId, songBId) {
        if (songAId.isNotEmpty() && songBId.isNotEmpty()) {
            viewModel.loadData(songAId, songBId)
        }
    }

    // Collect state from ViewModel
    val track1 by viewModel.track1.collectAsState()
    val track2 by viewModel.track2.collectAsState()

    val waveformData1 by viewModel.waveformBeatDomain1.collectAsState()
    val waveformData2 by viewModel.waveformBeatDomain2.collectAsState()

    val beatMarkers by viewModel.beatMarkers.collectAsState()

    val pixelsPerBeat by viewModel.pixelsPerBeatBase.collectAsState()

    // 0.0 to 1.0 (scrolling beat position) is confusing?
    // Actually, playbackBeatMarker from ViewModel is "Current Beat Index on A"
    val playbackBeatMarker by viewModel.playbackBeatMarker.collectAsState()

    val track1OffsetBeats by viewModel.track1OffsetBeats.collectAsState()
    val track2OffsetBeats by viewModel.track2OffsetBeats.collectAsState()

    val barsCount by viewModel.barsCount.collectAsState()
    val transitionWidthFraction by viewModel.transitionWidthFraction.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val overlapMode by viewModel.overlapMode.collectAsState()
    val eqMode by viewModel.eqMode.collectAsState()
    val effectMode by viewModel.effectMode.collectAsState()

    val isPlaying by viewModel.isPlaying.collectAsState()
    val decksReady by viewModel.areDecksReady.collectAsState()
    val loadingError by viewModel.loadingError.collectAsState()

    // Debug: Log state changes
    LaunchedEffect(decksReady, loadingError) {
        Log.d("TransitionEditorScreen", "UI State - decksReady: $decksReady, loadingError: $loadingError")
    }

    // UI VISIBILITY STATE
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            kotlinx.coroutines.delay(3000)
            controlsVisible = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = true })
            }
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()

        // Sync screen width to VM for zoom calc
        LaunchedEffect(screenWidthPx, barsCount) {
            viewModel.setScreenWidth(screenWidthPx)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                onCancel = onCancel,
                onSave = { viewModel.saveTransition(onComplete = onSave) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            track1?.let { TransitionTrackInfo(it, modifier = Modifier.padding(horizontal = 16.dp)) }

            Spacer(modifier = Modifier.height(24.dp))

            // Offsets are stored in Beats in VM, converted to Pixels for View
            val track1OffsetPixels = -track1OffsetBeats * pixelsPerBeat
            val track2OffsetPixels = -track2OffsetBeats * pixelsPerBeat

            WaveformsSection(
                track1 = track1,
                track2 = track2,
                waveformData1 = waveformData1,
                waveformData2 = waveformData2,
                beatMarkers = beatMarkers,
                pixelsPerBeat = pixelsPerBeat,
                transitionWidthFraction = transitionWidthFraction,
                barsCount = barsCount,
                isPlaying = isPlaying,
                showControls = controlsVisible,
                decksReady = decksReady,
                loadingError = loadingError,
                playbackBeatMarker = playbackBeatMarker?.toFloat(),
                track1OffsetPixels = track1OffsetPixels,
                track2OffsetPixels = track2OffsetPixels,
                track1OffsetBeats = track1OffsetBeats,
                overlapMode = overlapMode,
                eqMode = eqMode,
                effectMode = effectMode,
                onTrack1OffsetChanged = { px -> viewModel.setTrack1Offset(px, pixelsPerBeat) },
                onTrack2OffsetChanged = { px -> viewModel.setTrack2Offset(px, pixelsPerBeat) },
                onPlayPauseClick = { viewModel.togglePlayback() }
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
                onOverlapModeChanged = { viewModel.setOverlapMode(it) },
                eqMode = eqMode,
                onEqModeChanged = { viewModel.setEqMode(it) },
                effectMode = effectMode,
                onEffectModeChanged = { viewModel.setEffectMode(it) },
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
    beatMarkers: List<BeatGridMarker>,
    pixelsPerBeat: Float,
    transitionWidthFraction: Float,
    barsCount: Int,
    isPlaying: Boolean,
    showControls: Boolean,
    decksReady: Boolean,
    loadingError: String?,
    playbackBeatMarker: Float?,
    track1OffsetPixels: Float,
    track2OffsetPixels: Float,
    track1OffsetBeats: Float, // Needed for playhead alignment
    overlapMode: String,
    eqMode: String,
    effectMode: String,
    onTrack1OffsetChanged: (Float) -> Unit,
    onTrack2OffsetChanged: (Float) -> Unit,
    onPlayPauseClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
        Column {
            // Track 1
            key(barsCount, pixelsPerBeat) {
                WaveformView(
                    waveformData = waveformData1,
                    beatMarkers = beatMarkers,
                    markerPosition = BeatMarkerPosition.BOTTOM,
                    pixelsPerBeat = pixelsPerBeat,
                    beatOffsetBeats = 0f,
                    initialOffset = track1OffsetPixels,
                    onOffsetChanged = onTrack1OffsetChanged,
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
            }
            // Track 2
            key(barsCount, pixelsPerBeat) {
                WaveformView(
                    waveformData = waveformData2,
                    beatMarkers = beatMarkers,
                    markerPosition = BeatMarkerPosition.TOP,
                    pixelsPerBeat = pixelsPerBeat,
                    beatOffsetBeats = 0f,
                    initialOffset = track2OffsetPixels,
                    onOffsetChanged = onTrack2OffsetChanged,
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
            }
        }

        // Green Transition Box
        BoxWithConstraints(modifier = Modifier.fillMaxSize().align(Alignment.Center)) {
            val screenWidth = maxWidth
            val transitionWidth = screenWidth * transitionWidthFraction

            Box(
                modifier = Modifier
                    .width(transitionWidth)
                    .fillMaxHeight()
                    .align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF4CAF50).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(2.dp, Color(0xFF4CAF50).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                )

                // Visualization Curves
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    val w = size.width
                    val h = size.height
                    val steps = 50
                    val pathAVol = Path()
                    val pathBVol = Path()
                    // Reusable vars
                    var stateA: com.dd3boh.outertune.utils.DeckState
                    var stateB: com.dd3boh.outertune.utils.DeckState

                    for (i in 0..steps) {
                        val p = i / steps.toFloat()
                        val x = p * w

                        stateA = TransitionMixer.getMixState("A", p, overlapMode, eqMode, effectMode)
                        stateB = TransitionMixer.getMixState("B", p, overlapMode, eqMode, effectMode)

                        val yVolA = h - (stateA.volume * h)
                        val yVolB = h - (stateB.volume * h)

                        if (i == 0) {
                            pathAVol.moveTo(x, yVolA)
                            pathBVol.moveTo(x, yVolB)
                        } else {
                            pathAVol.lineTo(x, yVolA)
                            pathBVol.lineTo(x, yVolB)
                        }
                    }

                    drawPath(pathAVol, Color.White.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                    drawPath(pathBVol, Color.Cyan.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                }

                // Play/Pause Button
                AnimatedVisibility(
                    visible = !isPlaying || showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Surface(
                        onClick = { if (decksReady) onPlayPauseClick() },
                        shape = CircleShape,
                        color = Color(0xFF2C2C2C).copy(alpha = if (decksReady) 0.95f else 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (decksReady) Color(0xFF4CAF50) else if (loadingError != null) Color.Red else Color.Gray),
                        modifier = Modifier.size(64.dp),
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (loadingError != null) {
                                Icon(
                                    imageVector = Icons.Filled.Close, // Warning: needs context or import! Assuming generic Close or Warning
                                    contentDescription = "Error",
                                    tint = Color.Red,
                                    modifier = Modifier.size(32.dp)
                                )
                            } else if (!decksReady) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = Color.Gray,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = if (decksReady) Color.White else Color.Gray,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                // Header
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF4CAF50).copy(alpha = 0.9f)
                ) {
                    Text("Transition Zone", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }

                // Vertical Guide Lines
                Box(modifier = Modifier.width(2.dp).fillMaxHeight().align(Alignment.CenterStart).background(Color(0xFF4CAF50).copy(alpha = 0.8f)))
                Box(modifier = Modifier.width(2.dp).fillMaxHeight().align(Alignment.CenterEnd).background(Color(0xFF4CAF50).copy(alpha = 0.8f)))
            }
        }

        // Green Playhead Line
        // Calculation:
        // playbackBeatMarker is the current Beat Index on Track A.
        // track1OffsetPixels is the current visual scroll of Track A.
        // xPosition = (beatIndex * ppb) + scrollOffset
        if (playbackBeatMarker != null && pixelsPerBeat > 0) {
            val xPosition = (playbackBeatMarker * pixelsPerBeat) + track1OffsetPixels

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(
                    color = Color.Green,
                    start = Offset(xPosition, 0f),
                    end = Offset(xPosition, size.height),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
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
    val analysisBpm = song.song.bpm ?: 0f
    val displayBpm = song.song.displayBpm ?: analysisBpm

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.song.getThumbnailModel(),
            contentDescription = "Album Art",
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(4.dp)),
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
                text = "${displayBpm.toInt()} BPM",
                color = Color.White,
                fontSize = 12.sp
            )

            if (displayBpm != analysisBpm) {
                Text(
                    text = "detected: ${analysisBpm.toInt()} BPM",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp
                )
            }

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
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ControlSelector(
                title = "Volume",
                value = overlapMode,
                onClick = {
                    onTabSelected(0)
                    showBottomSheet = true
                },
                modifier = Modifier.weight(1f)
            )


            ControlSelector(
                title = "EQ",
                value = displayEqLabel(eqMode),
                onClick = {
                    onTabSelected(1)
                    showBottomSheet = true
                },
                modifier = Modifier.weight(1f)
            )

            ControlSelector(
                title = "Effect",
                value = displayEffectLabel(effectMode),
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
fun ControlSelector(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(
                indication = null, // no ripple
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // Small title
        Text(
            text = title,
            fontSize = 12.sp,
            color = Color(0xFF9A9A9A)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Value row (text + chevron)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )

            Text(
                text = "⌄",
                fontSize = 14.sp,
                color = Color(0xFF7A7A7A)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Subtle divider
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(1.dp)
                .background(
                    Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(1.dp)
                )
        )
    }
}

private fun displayEqLabel(mode: String): String =
    when (mode) {
        "Centre Bass swap" -> "Centre bass"
        "End Bass Swap" -> "End Bass"
        "Onset Bass Swap" -> "Onset Bass"
        else -> mode
    }

private fun displayEffectLabel(mode: String): String =
    when (mode) {
        "Low pass in" -> "LP in"
        "Low Pass out" -> "LP out"
        "High Pass in" -> "HP in"
        "High Pass Out" -> "HP out"
        else -> mode
    }
