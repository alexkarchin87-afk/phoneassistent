package com.voiceagent.oneplus13.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gemini-подобная 4-лучевая искра с вогнутыми гранями: каждая «грань» — квадратичная
 * кривая, управляющая точка которой почти в центре ([waist] — насколько тонкая талия).
 */
fun sparklePath(cx: Float, cy: Float, r: Float, waist: Float = 0.13f): Path {
    val k = r * waist
    return Path().apply {
        moveTo(cx, cy - r)
        quadraticBezierTo(cx + k, cy - k, cx + r, cy)
        quadraticBezierTo(cx + k, cy + k, cx, cy + r)
        quadraticBezierTo(cx - k, cy + k, cx - r, cy)
        quadraticBezierTo(cx - k, cy - k, cx, cy - r)
        close()
    }
}

/**
 * Фирменный знак: одна большая искра (как у Gemini) либо [cluster] — большая + две малые
 * («auto awesome»). При [animated] плавно «дышит» и медленно вращается.
 */
@Composable
fun SparkleMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = Color.Unspecified,
    brush: Brush? = null,
    animated: Boolean = false,
    cluster: Boolean = false
) {
    val tint = if (color == Color.Unspecified) LocalContentColor.current else color
    if (animated) {
        val t = rememberInfiniteTransition(label = "sparkle")
        val breathe = t.animateFloat(
            initialValue = 0.86f, targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe"
        )
        val spin = t.animateFloat(
            initialValue = 0f, targetValue = 90f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
            label = "spin"
        )
        Canvas(modifier.size(size)) { drawSparkle(tint, brush, cluster, breathe.value, spin.value) }
    } else {
        Canvas(modifier.size(size)) { drawSparkle(tint, brush, cluster, 1f, 0f) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkle(
    tint: Color, brush: Brush?, cluster: Boolean, scaleFactor: Float, angle: Float
) {
    val w = size.minDimension
    scale(scaleFactor, pivot = center) {
        rotate(angle, pivot = center) {
            if (cluster) {
                val big = sparklePath(w * 0.42f, w * 0.58f, w * 0.40f)
                val small = sparklePath(w * 0.80f, w * 0.20f, w * 0.17f)
                val tiny = sparklePath(w * 0.84f, w * 0.72f, w * 0.10f)
                listOf(big, small, tiny).forEach { p ->
                    if (brush != null) drawPath(p, brush) else drawPath(p, tint)
                }
            } else {
                val p = sparklePath(w / 2f, w / 2f, w * 0.5f)
                if (brush != null) drawPath(p, brush) else drawPath(p, tint)
            }
        }
    }
}

/** Лёгкая версия (старое имя сохранено). */
@Composable
fun SparkIcon(
    size: Dp = 24.dp,
    color: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") strokeWidth: Dp = 1.5.dp
) {
    SparkleMark(size = size, color = color)
}

/**
 * Круглый бейдж с искрой — значок вызова в шапках, на экране входа и т.п.
 * По умолчанию инвертируется вместе с темой (светлый круг на тёмном, и наоборот).
 */
@Composable
fun SparkIconPro(
    size: Dp = 56.dp,
    badgeColor: Color = Color.Unspecified,
    glyphColor: Color = Color.Unspecified,
    animated: Boolean = false
) {
    val badge = if (badgeColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else badgeColor
    val glyph = if (glyphColor == Color.Unspecified) MaterialTheme.colorScheme.surface else glyphColor
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.verticalGradient(listOf(badge, badge.copy(alpha = 0.78f))),
                radius = r
            )
            drawCircle(
                color = glyph.copy(alpha = 0.18f),
                radius = r - 0.75.dp.toPx(),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        SparkleMark(size = size * 0.56f, color = glyph, animated = animated)
    }
}

// ── Совместимые обёртки для старых вызовов ────────────────────────────────────

@Composable
private fun glyph(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp, color: Color) {
    val tint = if (color == Color.Unspecified) LocalContentColor.current else color
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
fun CloseGlyph(size: Dp = 16.dp, color: Color = Color.Unspecified) = glyph(VaIcons.Close, size, color)

@Composable
fun MicGlyph(size: Dp = 20.dp, color: Color = Color.Unspecified) = glyph(VaIcons.Mic, size, color)

@Composable
fun SendArrowGlyph(size: Dp = 16.dp, color: Color = Color.Unspecified) = glyph(VaIcons.ArrowUp, size, color)
