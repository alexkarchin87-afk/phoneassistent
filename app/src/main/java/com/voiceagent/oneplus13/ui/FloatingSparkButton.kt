package com.voiceagent.oneplus13.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Плавающая круглая кнопка. Открывает панель чата.
 * isListening — используется для лёгкой пульсации, когда агент реально слушает
 * (пробрасывается из статуса AgentEventBus.lastStatus в MainScreen).
 */
@Composable
fun FloatingSparkButton(
    isListening: Boolean = false,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "fab_pulse")
    val scale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isListening) {
            ListeningRings(size = size)
        } else {
            SparkIconPro(size = size * 0.92f, badgeColor = Color.Black, glyphColor = Color.White)
        }
    }
}

@Composable
private fun ListeningRings(size: Dp) {
    val infinite = rememberInfiniteTransition(label = "listening_rings")
    val ringScale by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing)
        ),
        label = "ring_scale"
    )
    val ringAlpha by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing)
        ),
        label = "ring_alpha"
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * ringScale)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = ringAlpha * 0.15f))
        )
        SparkIconPro(size = size * 0.72f, badgeColor = Color.Black, glyphColor = Color.White)
    }
}

