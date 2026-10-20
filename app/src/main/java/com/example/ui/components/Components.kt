package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ReactionEvent
import com.example.ui.theme.VibeAccentCyan
import com.example.ui.theme.VibePink
import com.example.ui.theme.VibePurpleDark
import com.example.ui.theme.VibeRed
import com.example.ui.theme.VibeSurfaceVariant
import kotlinx.coroutines.delay

@Composable
fun RadarSearchingView(
    onlineUsersCount: Int = 1,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )

    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha1"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2"
    )

    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha2"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VibePurpleDark),
        contentAlignment = Alignment.Center
    ) {
        // Expanding ring 1
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulse1)
                .alpha(alpha1)
                .border(2.dp, VibePink, CircleShape)
        )

        // Expanding ring 2
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(pulse2)
                .alpha(alpha2)
                .border(2.dp, VibeAccentCyan, CircleShape)
        )

        // Center Pulsing Radar Core
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(VibeRed, VibePink, Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Searching",
                tint = Color.White,
                modifier = Modifier.size(54.dp)
            )
        }

        // Searching text & Live count badge
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x3300E676))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E676))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$onlineUsersCount real user${if (onlineUsersCount > 1) "s" else ""} online",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Searching for real users…",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                ),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Waiting for another real person to connect. Swipe or hold on to vibe!",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFFC3B6D4)
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun FullscreenEmojiBurst(
    reaction: ReactionEvent?,
    onFinished: () -> Unit
) {
    if (reaction == null) return

    var visible by remember(reaction.id) { mutableStateOf(true) }
    val scale = remember(reaction.id) { Animatable(0.2f) }

    LaunchedEffect(reaction.id) {
        scale.animateTo(
            targetValue = 1.3f,
            animationSpec = tween(400, easing = FastOutSlowInEasing)
        )
        scale.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        )
        delay(800)
        visible = false
        delay(300)
        onFinished()
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(300)) + fadeIn(tween(200)),
        exit = scaleOut(tween(300)) + fadeOut(tween(300))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .scale(scale.value)
                    .clip(CircleShape)
                    .background(Color(0x77000000))
                    .border(2.dp, VibePink.copy(alpha = 0.6f), CircleShape)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = reaction.emoji,
                    fontSize = 80.sp
                )
            }
        }
    }
}

@Composable
fun ThreeDotsTypingIndicator(
    peerName: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    val dot1Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )

    val dot2Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 130, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )

    val dot3Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 260, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x991E1528))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$peerName is typing",
            style = MaterialTheme.typography.labelSmall.copy(
                color = VibeAccentCyan,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .offset(y = dot1Offset.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(VibePink)
        )
        Box(
            modifier = Modifier
                .offset(y = dot2Offset.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(VibePink)
        )
        Box(
            modifier = Modifier
                .offset(y = dot3Offset.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(VibePink)
        )
    }
}
