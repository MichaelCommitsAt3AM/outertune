package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.ui.component.BeatMarkerPosition
import com.dd3boh.outertune.ui.component.WaveformView

@Composable
fun TransitionEditorScreen(
    onCancel: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) }
    var overlapMode by remember { mutableStateOf("Overlap") }
    var eqMode by remember { mutableStateOf("None") }
    var effectMode by remember { mutableStateOf("Low pass filt...") }
    var barsCount by remember { mutableStateOf("4 bars") }

    val track1 = remember {
        Track(
            title = "Hi",
            artist = "Vybz Kartel",
            bpm = 95,
            duration = "02:38",
            albumArt = com.yourapp.R.drawable.album_art_1
        )
    }

    val track2 = remember {
        Track(
            title = "Loodi (feat. Vybz Kartel)",
            artist = "Shenseea",
            bpm = 96,
            duration = "03:12",
            albumArt = com.yourapp.R.drawable.album_art_2
        )
    }

    val beatMarkers1 = remember {
        (0..10).map { it * 200f }
    }

    val beatMarkers2 = remember {
        (0..10).map { it * 200f + 50f }
    }

    val waveformData1 = remember {
        FloatArray(2000) { index ->
            (Math.sin(index * 0.05) * 0.5f + Math.random() * 0.3f).toFloat()
        }
    }

    val waveformData2 = remember {
        FloatArray(2000) { index ->
            (Math.sin(index * 0.03) * 0.5f + Math.random() * 0.3f).toFloat()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
            TrackInfo(
                track = track1,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Waveforms
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                Column {
                    WaveformView(
                        waveformData = waveformData1,
                        beatMarkers = beatMarkers1,
                        markerPosition = BeatMarkerPosition.BOTTOM,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )

                    WaveformView(
                        waveformData = waveformData2,
                        beatMarkers = beatMarkers2,
                        markerPosition = BeatMarkerPosition.TOP,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }

                // Transition Overlay
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

            // Bars Dropdown
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2C2C2C)
                ) {
                    Text(
                        text = "$barsCount ⌄",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Track 2 Info
            TrackInfo(
                track = track2,
                showDurationBadge = false,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

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

            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF4CAF50)
            ) {
                Text(
                    text = "Beta",
                    color = Color.Black,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
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
fun TrackInfo(
    track: Track,
    showDurationBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = track.albumArt),
            contentDescription = "Album Art",
            modifier = Modifier.size(60.dp),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = track.artist,
                color = Color(0xFFAAAAAA),
                fontSize = 14.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "${track.bpm} bpm",
                color = Color.White,
                fontSize = 12.sp
            )

            if (showDurationBadge) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF3949AB)
                ) {
                    Text(
                        text = track.duration,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                Text(
                    text = track.duration,
                    color = Color.White,
                    fontSize = 14.sp
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C1C1C),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TabItem(
                    text = "Volume",
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    modifier = Modifier.weight(1f)
                )

                TabItem(
                    text = "EQ",
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    modifier = Modifier.weight(1f)
                )

                TabItem(
                    text = "Effect",
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dropdowns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DropdownOption(
                    value = overlapMode,
                    options = listOf("Overlap", "Crossfade", "Cut"),
                    onValueChange = onOverlapModeChanged,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                DropdownOption(
                    value = eqMode,
                    options = listOf("None", "Low pass", "High pass", "Band pass"),
                    onValueChange = onEqModeChanged,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                DropdownOption(
                    value = effectMode,
                    options = listOf("Low pass filt...", "Echo", "Reverb", "None"),
                    onValueChange = onEffectModeChanged,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun TabItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF4CAF50) else Color.White,
            fontSize = 14.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownOption(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        TextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF2C2C2C),
                unfocusedContainerColor = Color(0xFF2C2C2C),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

data class Track(
    val title: String,
    val artist: String,
    val bpm: Int,
    val duration: String,
    val albumArt: Int,
    val audioFile: String? = null
)
