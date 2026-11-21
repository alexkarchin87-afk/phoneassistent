package com.voiceagent.oneplus13.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.voiceagent.oneplus13.core.ResponseMode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    /** Путь к .zip сгенерированного проекта (см. AgentEvent.ProjectReady) — тогда под
     *  текстом рисуется карточка «Открыть/Поделиться». Для обычных сообщений — null. */
    val attachmentPath: String? = null,
    val attachmentLabel: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
//  Состояние агента (STT → обработка → TTS)
// ─────────────────────────────────────────────────────────────────────────────

enum class AgentPhase(val title: String, val caption: String, val step: Int) {
    OFF("Агент выключен", "Включите, чтобы Ваня слушал в фоне", -1),
    IDLE("Готов", "Скажите «Ваня» или напишите запрос", -1),
    LISTENING("Слушаю…", "Распознаю речь", 0),
    HEARD("Услышал", "Передаю запрос агенту", 0),
    THINKING("Думаю…", "Обрабатываю запрос", 1),
    ACTING("Выполняю", "Действие на устройстве", 1),
    SPEAKING("Отвечаю", "Озвучиваю и показываю ответ", 2),
    ERROR("Ошибка", "Что-то пошло не так", -1);

    /** Идёт «живая» работа — индикаторы анимируются. */
    val isBusy: Boolean get() = this == LISTENING || this == THINKING || this == ACTING || this == SPEAKING
}

/** Разбор строки из [com.voiceagent.oneplus13.core.AgentEventBus.lastStatus]. */
fun resolvePhase(running: Boolean, status: String): AgentPhase {
    if (!running) return AgentPhase.OFF
    val s = status.trim().lowercase()
    return when {
        s.startsWith("слуш") -> AgentPhase.LISTENING
        s.startsWith("услыш") -> AgentPhase.HEARD
        s.startsWith("дума") -> AgentPhase.THINKING
        s.startsWith("действ") -> AgentPhase.ACTING
        s.startsWith("ответ") || s.startsWith("проект") -> AgentPhase.SPEAKING
        s.startsWith("ошиб") -> AgentPhase.ERROR
        else -> AgentPhase.IDLE
    }
}

/**
 * lastStatus на шине никогда не сбрасывается сам («ответ: …» висит вечно), поэтому
 * кратковременные фазы через паузу возвращаются в IDLE — иначе индикатор «работал» бы бесконечно.
 */
@Composable
fun rememberAgentPhase(running: Boolean, status: String): AgentPhase {
    val raw = resolvePhase(running, status)
    var phase by remember { mutableStateOf(raw) }
    LaunchedEffect(raw, status) {
        phase = raw
        val timeout = when (raw) {
            AgentPhase.LISTENING -> 20_000L
            AgentPhase.HEARD -> 6_000L
            AgentPhase.THINKING -> 90_000L
            AgentPhase.ACTING -> 8_000L
            AgentPhase.SPEAKING -> 6_000L
            AgentPhase.ERROR -> 10_000L
            else -> 0L
        }
        if (timeout > 0) {
            delay(timeout)
            phase = AgentPhase.IDLE
        }
    }
    return phase
}

private fun statusDetail(status: String, phase: AgentPhase): String {
    val tail = status.substringAfter(": ", missingDelimiterValue = "").trim()
    return if (tail.isNotEmpty() && phase != AgentPhase.IDLE && phase != AgentPhase.OFF) tail else phase.caption
}

/** Эквалайзер-индикатор активности. В покое — низкие неподвижные полоски. */
@Composable
fun Equalizer(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    bars: Int = 5,
    height: Dp = 18.dp
) {
    val tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    if (active) {
        val t = rememberInfiniteTransition(label = "eq")
        val levels = List(bars) { i ->
            t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420 + i * 110, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar$i"
            )
        }
        EqCanvas(modifier, bars, height, tint) { levels[it].value }
    } else {
        EqCanvas(modifier, bars, height, tint.copy(alpha = 0.35f)) { 0.25f }
    }
}

@Composable
private fun EqCanvas(modifier: Modifier, bars: Int, height: Dp, tint: Color, level: (Int) -> Float) {
    val barW = 3.dp
    val gap = 3.dp
    Canvas(modifier.size(width = barW * bars + gap * (bars - 1), height = height)) {
        val bw = barW.toPx()
        val g = gap.toPx()
        for (i in 0 until bars) {
            val h = size.height * level(i)
            drawRoundRect(
                color = tint,
                topLeft = Offset(i * (bw + g), (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f)
            )
        }
    }
}

/** Карточка статуса: большая — на главном экране. */
@Composable
fun AgentStatusCard(
    phase: AgentPhase,
    statusText: String,
    running: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainerLow)))
            .border(1.dp, cs.outlineVariant, shape)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHighest)
                    .border(1.dp, cs.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                SparkleMark(size = 24.dp, brush = monoGradientBrush(), animated = phase.isBusy)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(phase.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Equalizer(active = phase.isBusy, height = 14.dp, bars = 4)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    statusDetail(statusText, phase),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (running) cs.onSurface else Color.Transparent)
                    .border(1.dp, if (running) cs.onSurface else cs.outline, CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    VaIcons.Power,
                    contentDescription = if (running) "Остановить агента" else "Запустить агента",
                    tint = if (running) cs.surface else cs.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        PipelineRow(phase)
    }
}

@Composable
private fun monoGradientBrush(): Brush {
    val c = MaterialTheme.colorScheme.onSurface
    return Brush.linearGradient(listOf(c, c.copy(alpha = 0.5f)))
}

@Composable
private fun PipelineRow(phase: AgentPhase) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        PipelineStep("STT", "Слушаю", VaIcons.Mic, 0, phase, Modifier.weight(1f))
        PipelineStep("LLM", "Думаю", VaIcons.Pulse, 1, phase, Modifier.weight(1f))
        PipelineStep("TTS", "Отвечаю", VaIcons.Volume, 2, phase, Modifier.weight(1f))
    }
}

@Composable
private fun PipelineStep(tag: String, label: String, icon: ImageVector, index: Int, phase: AgentPhase, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val active = phase.step == index
    val done = phase.step > index
    val bg = when {
        active -> cs.onSurface
        done -> cs.surfaceContainerHighest
        else -> Color.Transparent
    }
    val fg = when {
        active -> cs.surface
        done -> cs.onSurface
        else -> cs.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, if (active) cs.onSurface else cs.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(tag, style = MaterialTheme.typography.labelSmall, color = fg)
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}

/** Компактная «таблетка» статуса — над чатом, когда главный экран уже в режиме диалога. */
@Composable
fun StatusPill(phase: AgentPhase, statusText: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Equalizer(active = phase.isBusy, height = 12.dp, bars = 4)
        Spacer(Modifier.width(10.dp))
        Text(phase.title, style = MaterialTheme.typography.labelLarge)
        val detail = statusDetail(statusText, phase)
        if (detail != phase.caption) {
            Spacer(Modifier.width(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Сообщения
// ─────────────────────────────────────────────────────────────────────────────

/** Стиль Gemini: реплика пользователя — мягкая «таблетка» справа, ответ агента — просто текст с искрой. */
@Composable
fun ChatBubble(message: ChatMessage, compact: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    if (message.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = if (compact) 250.dp else 320.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(message.text, style = textStyle)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            SparkleMark(
                modifier = Modifier.padding(top = 3.dp),
                size = if (compact) 16.dp else 20.dp,
                brush = monoGradientBrush()
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(message.text, style = textStyle)
                if (message.attachmentPath != null) {
                    Spacer(Modifier.height(8.dp))
                    AttachmentChip(
                        label = message.attachmentLabel ?: "project.zip",
                        path = message.attachmentPath
                    )
                }
            }
        }
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)
    ) {
        items(messages) { ChatBubble(it, compact) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Ввод
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Поле ввода-«пилюля». Справа: стрелка отправки (если есть текст); иначе — микрофон
 * ([onMicClick] != null → запускает агента), либо анимированная искра, пока агент слушает.
 */
@Composable
fun Composer(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Спросите Ваню",
    listening: Boolean = false,
    onMicClick: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val mode = remember(context) { ResponseMode(context) }
    var thoughtful by remember { mutableStateOf(mode.thoughtful) }
    var modeMenu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val canSend = input.isNotBlank()
    val shape = RoundedCornerShape(28.dp)

    fun submit() {
        if (input.isNotBlank()) {
            onSend(input.trim())
            input = ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, shape)
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { modeMenu = true }) {
                Text(if (thoughtful) "Вдумчиво ▾" else "Быстро ▾", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                DropdownMenuItem(text = { Text("Быстро — короче и быстрее") }, onClick = {
                    thoughtful = false; mode.thoughtful = false; modeMenu = false
                })
                DropdownMenuItem(text = { Text("Вдумчиво — дольше, с проверкой") }, onClick = {
                    thoughtful = true; mode.thoughtful = true; modeMenu = false
                })
            }
        }
        TextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder, color = cs.onSurfaceVariant) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = cs.onSurface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() })
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) cs.onSurface else Color.Transparent)
                .let {
                    when {
                        canSend -> it.clickable { submit() }
                        onMicClick != null && !listening -> it.clickable(onClick = onMicClick)
                        else -> it
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                canSend -> Icon(VaIcons.ArrowUp, "Отправить", tint = cs.surface, modifier = Modifier.size(22.dp))
                listening -> SparkleMark(size = 22.dp, brush = monoGradientBrush(), animated = true)
                else -> Icon(VaIcons.Mic, "Микрофон", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Плотная «пилюля» с подсказкой (быстрая команда). */
@Composable
fun PromptPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, cs.outlineVariant)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            maxLines = 1
        )
    }
}
