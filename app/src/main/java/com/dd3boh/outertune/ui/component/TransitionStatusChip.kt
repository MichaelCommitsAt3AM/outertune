package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.transition.model.TransitionState

/**
 * A Material 3 status chip that visually indicates the state of a transition between songs.
 *
 * This component is designed for use in scrolling lists (LazyColumn) with optimal performance:
 * - Stateless design (state is passed from ViewModel)
 * - No internal recomposition triggers
 * - Accessible with proper content descriptions
 *
 * @param state The current transition state (Auto or Custom)
 * @param onClick Callback invoked when the chip is tapped
 * @param modifier Optional modifier for this composable
 */
@Composable
fun TransitionStatusChip(
    state: TransitionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCustom = state is TransitionState.Custom
    
    val backgroundColor = if (isCustom) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    
    val contentColor = if (isCustom) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val iconAlpha = if (isCustom) 1.0f else 0.6f
    
    val contentDesc = if (isCustom) {
        "Custom transition configured"
    } else {
        "Auto generated transition"
    }
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(28.dp)
            .semantics {
                role = Role.Button
                contentDescription = contentDesc
            },
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Crossfade/Waveform Icon
            Icon(
                painter = painterResource(R.drawable.shuffle_on),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor.copy(alpha = iconAlpha)
            )
            
            // Label Text
            Text(
                text = if (isCustom) "CUSTOM" else "AUTO",
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = if (isCustom) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
            
            // Custom Indicator Dot (only for Custom state)
            if (isCustom) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

/**
 * Alternative compact variant without text, showing only icon and indicator.
 * Useful for very dense layouts.
 */
@Composable
fun TransitionStatusChipCompact(
    state: TransitionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCustom = state is TransitionState.Custom
    
    val backgroundColor = if (isCustom) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    
    val iconColor = if (isCustom) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    
    val contentDesc = if (isCustom) {
        "Custom transition configured"
    } else {
        "Auto generated transition"
    }
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(24.dp)
            .semantics {
                role = Role.Button
                contentDescription = contentDesc
            },
        shape = CircleShape,
        color = backgroundColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.shuffle_on),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = iconColor
            )
            
            // Small indicator dot in top-right corner for Custom state
            if (isCustom) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape
                        )
                )
            }
        }
    }
}
