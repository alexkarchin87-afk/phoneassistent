package com.voiceagent.oneplus13.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val BubbleTop = Color(0xFF3A3A40)
private val BubbleBottom = Color(0xFF070708)
private val Halo = Color(0xFF8E8E93)

/**
 * Плавающая кнопка-искра в стиле Gemini: тёмное «стеклянное» ядро с тонким светлым
 * кантом и белой градиентной искрой. Тёмная в обеих темах — читается на любом фоне,
 * в т.ч. поверх чужих приложений (оверлей).
 *
 * [isListening] — пульсирующие кольца-«эхо» и медленное вращение искры. Когда
 * кнопка в покое, анимаций нет вообще (важно для постоянно висящего оверлея).
 * Все эффекты рисуются внутри [size], чтобы окно WindowManager их не обрезало.
 */
@Composable
fun FloatingSparkButton(
    isListening: Boolean = false,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isListening) {
            val t = rememberInfiniteTransition(label = "spark_fab")
            val pulse = t.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
                label = "pulse"
            )
            val spin = t.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
                label = "spin"
            )
            Canvas(Modifier.size(size)) { drawBubble(pulse.value, spin.value, listening = true) }
        } else {
            Canvas(Modifier.size(size)) { drawBubble(0f, 0f, listening = false) }
        }
    }
}

private fun DrawScope.drawBubble(pulse: Float, spin: Float, listening: Boolean) {
    val half = this.size.minDimension / 2f
    val core = half * 0.78f
    val c = center

    if (listening) {
        repeat(2) { i ->
            val p = (pulse + i * 0.5f) % 1f
            val r = core + (half - core) * p
            val a = 1f - p
            drawCircle(Halo.copy(alpha = a * 0.22f), radius = r, center = c)
            drawCircle(Halo.copy(alpha = a * 0.55f), radius = r, center = c, style = Stroke(1.dp.toPx()))
        }
    }

    // ядро
    drawCircle(
        brush = Brush.verticalGradient(listOf(BubbleTop, BubbleBottom)),
        radius = core,
        center = c
    )
    // тонкий светлый кант
    drawCircle(
        brush = Brush.sweepGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.55f)),
            center = c
        ),
        radius = core - 0.6.dp.toPx(),
        center = c,
        style = Stroke(1.2.dp.toPx())
    )
    // искра
    rotate(degrees = spin, pivot = c) {
        drawPath(
            path = sparklePath(c.x, c.y, core * 0.52f),
            brush = Brush.linearGradient(
                colors = listOf(Color.White, Color(0xFFB4B4BB)),
                start = androidx.compose.ui.geometry.Offset(c.x - core * 0.5f, c.y - core * 0.5f),
                end = androidx.compose.ui.geometry.Offset(c.x + core * 0.5f, c.y + core * 0.5f)
            )
        )
        // маленькая «искорка-спутник»
        drawPath(
            path = sparklePath(c.x + core * 0.46f, c.y - core * 0.46f, core * 0.17f),
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}
