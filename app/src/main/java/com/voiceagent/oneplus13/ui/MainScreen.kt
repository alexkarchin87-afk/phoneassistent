package com.voiceagent.oneplus13.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voiceagent.oneplus13.R
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.ui.theme.isDarkScheme
import com.voiceagent.oneplus13.ui.theme.monoGradient

/**
 * Callbacks, которые MainActivity даёт экрану — реальные действия (управление
 * сервисом, системные разрешения, экран настроек), а не заглушки. Без изменений.
 */
data class AgentControls(
    val isAgentRunning: Boolean,
    val onStartAgent: () -> Unit,
    val onStopAgent: () -> Unit,
    val onSendText: (String) -> Unit,
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onOpenBatterySettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenSystemAppInfo: () -> Unit,
    val onOpenInstallSettings: () -> Unit,
    // Системный оверлей-пузырь (виден поверх ЛЮБОГО приложения) — см. overlay/OverlayBubbleController.kt.
    val canDrawOverlays: () -> Boolean,
    val onRequestOverlayPermission: () -> Unit,
    val isOverlayEnabled: () -> Boolean,
    val onSetOverlayEnabled: (Boolean) -> Unit,
    val isOverlayAlwaysVisible: () -> Boolean,
    val onSetOverlayAlwaysVisible: (Boolean) -> Unit,
    val onOpenWorkflowWorkshop: () -> Unit
)

private val QUICK_PROMPTS = listOf("Который час?", "Какой сегодня день?", "Что сейчас играет?", "Что нового?", "Заверши лекцию")

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun MainScreen(windowSizeClass: WindowSizeClass, controls: AgentControls) {
    val context = LocalContext.current
    val uiPrefs = remember { UiPreferences(context) }
    var formFactorOverride by remember { mutableStateOf(uiPrefs.formFactorOverride) }
    val isWindowsHost = remember { isLikelyWindowsHost(context) }
    val formFactor = windowSizeClass.toFormFactor(override = formFactorOverride, isWindowsHost = isWindowsHost)

    val historyStore = remember { CommandHistory(context) }
    var history by remember { mutableStateOf(historyStore.all()) }
    var panelVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var historySheetVisible by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val statusText by AgentEventBus.lastStatus.collectAsState()
    val phase = rememberAgentPhase(controls.isAgentRunning, statusText)

    // Реальные события агента (услышанное голосом, ответы, ошибки) стекаются
    // в тот же чат, что и текст, набранный руками.
    LaunchedEffect(Unit) {
        AgentEventBus.events.collect { event ->
            when (event) {
                is AgentEvent.Heard -> {
                    messages.add(ChatMessage(event.text, isUser = true))
                    history = historyStore.add(event.text)
                }
                is AgentEvent.Reply -> messages.add(ChatMessage(event.text, isUser = false))
                is AgentEvent.Error -> messages.add(ChatMessage("⚠ ${event.message}", isUser = false))
                is AgentEvent.ProjectReady -> messages.add(
                    ChatMessage(
                        text = "Готово — проект «${event.projectName}» собран в zip.",
                        isUser = false,
                        attachmentPath = event.zipPath,
                        attachmentLabel = java.io.File(event.zipPath).name
                    )
                )
                is AgentEvent.WakeDetected -> panelVisible = true
                else -> Unit
            }
        }
    }

    val handleSendText: (String) -> Unit = { text ->
        // Показываем сразу же — не ждём, пока событие вернётся с шины.
        messages.add(ChatMessage(text, isUser = true))
        history = historyStore.add(text)
        if (controls.isAgentRunning) {
            controls.onSendText(text)
        } else {
            messages.add(ChatMessage("Сначала включи агента, затем отправь сообщение ещё раз.", isUser = false))
        }
    }
    val toggleAgent: () -> Unit = { if (controls.isAgentRunning) controls.onStopAgent() else controls.onStartAgent() }
    val openSettings = { settingsVisible = true }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AmbientGlow()

        when (formFactor) {
            DeviceFormFactor.PHONE -> PhoneLayout(
                phase = phase, statusText = statusText, running = controls.isAgentRunning,
                messages = messages, history = history,
                onSend = handleSendText, onToggleAgent = toggleAgent,
                onNewChat = { messages.clear() },
                onOpenHistory = { historySheetVisible = true },
                onClearHistory = { history = historyStore.clear() },
                onOpenSettings = openSettings
            )
            DeviceFormFactor.TABLET_OR_DESKTOP -> DesktopLayout(
                phase = phase, statusText = statusText, running = controls.isAgentRunning,
                messages = messages, history = history,
                onSend = handleSendText, onToggleAgent = toggleAgent,
                onClearHistory = { history = historyStore.clear() },
                onOpenSettings = openSettings
            )
            DeviceFormFactor.WINDOWS -> WindowsLayout(
                phase = phase, statusText = statusText, running = controls.isAgentRunning,
                panelVisible = panelVisible, messages = messages, history = history,
                onFabClick = { panelVisible = true }, onDismiss = { panelVisible = false },
                onSend = handleSendText, onToggleAgent = toggleAgent,
                onClearHistory = { history = historyStore.clear() },
                onOpenSettings = openSettings
            )
        }

        AnimatedVisibility(
            visible = settingsVisible,
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(160))
        ) {
            SettingsHubScreen(
                controls = controls,
                currentOverride = formFactorOverride,
                onOverrideChange = { newOverride ->
                    formFactorOverride = newOverride
                    uiPrefs.formFactorOverride = newOverride
                },
                onBack = { settingsVisible = false }
            )
        }
    }

    BackHandler(enabled = settingsVisible) { settingsVisible = false }

    if (historySheetVisible) {
        HistorySheet(
            history = history,
            onPick = { text ->
                historySheetVisible = false
                handleSendText(text)
            },
            onClear = { history = historyStore.clear() },
            onDismiss = { historySheetVisible = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Раскладки
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PhoneLayout(
    phase: AgentPhase,
    statusText: String,
    running: Boolean,
    messages: List<ChatMessage>,
    history: List<HistoryItem>,
    onSend: (String) -> Unit,
    onToggleAgent: () -> Unit,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        HomeTopBar(
            onNewChat = if (messages.isNotEmpty()) onNewChat else null,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings
        )
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                HomeFeed(
                    phase = phase, statusText = statusText, running = running,
                    history = history,
                    onPrompt = onSend, onToggleAgent = onToggleAgent, onClearHistory = onClearHistory,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    StatusPill(
                        phase = phase, statusText = statusText,
                        onClick = if (!running) onToggleAgent else null,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    MessageList(messages = messages, modifier = Modifier.weight(1f))
                }
            }
        }
        Composer(
            onSend = onSend,
            listening = phase == AgentPhase.LISTENING,
            onMicClick = if (!running) onToggleAgent else null,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
        )
    }
}

@Composable
private fun DesktopLayout(
    phase: AgentPhase,
    statusText: String,
    running: Boolean,
    messages: List<ChatMessage>,
    history: List<HistoryItem>,
    onSend: (String) -> Unit,
    onToggleAgent: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            HomeTopBar(onNewChat = null, onOpenHistory = null, onOpenSettings = onOpenSettings)
            HomeFeed(
                phase = phase, statusText = statusText, running = running, history = history,
                onPrompt = onSend, onToggleAgent = onToggleAgent, onClearHistory = onClearHistory,
                modifier = Modifier.weight(1f)
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.width(420.dp).fillMaxHeight()) {
            AgentSiriPanel(
                isVisible = true,
                messages = messages,
                quickPrompts = QUICK_PROMPTS,
                onDismiss = {},
                onSend = onSend,
                onQuickPrompt = onSend,
                dockedMode = true,
                isListening = phase == AgentPhase.LISTENING
            )
        }
    }
}

/**
 * Windows-режим: главный экран виден целиком, чат — компактная карточка в правом нижнем
 * углу поверх контента (как Siri/Cortana), вызывается искрой.
 */
@Composable
private fun WindowsLayout(
    phase: AgentPhase,
    statusText: String,
    running: Boolean,
    panelVisible: Boolean,
    messages: List<ChatMessage>,
    history: List<HistoryItem>,
    onFabClick: () -> Unit,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onToggleAgent: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(onNewChat = null, onOpenHistory = null, onOpenSettings = onOpenSettings)
            HomeFeed(
                phase = phase, statusText = statusText, running = running, history = history,
                onPrompt = onSend, onToggleAgent = onToggleAgent, onClearHistory = onClearHistory,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 110.dp)
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.End
        ) {
            WindowsCornerPopup(
                isVisible = panelVisible,
                isListening = phase == AgentPhase.LISTENING || phase == AgentPhase.THINKING,
                messages = messages,
                quickPrompts = QUICK_PROMPTS,
                onDismiss = onDismiss,
                onSend = onSend,
                onQuickPrompt = onSend
            )
            Spacer(Modifier.height(14.dp))
            FloatingSparkButton(
                isListening = phase == AgentPhase.LISTENING || phase == AgentPhase.THINKING,
                size = 60.dp,
                onClick = { if (panelVisible) onDismiss() else onFabClick() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Блоки главного экрана
// ─────────────────────────────────────────────────────────────────────────────

/** Мягкое свечение сверху — единственный «декор» фона, без цвета (только оттенки серого). */
@Composable
private fun AmbientGlow() {
    val onBg = MaterialTheme.colorScheme.onBackground
    val alpha = if (isDarkScheme()) 0.10f else 0.06f
    Box(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(onBg.copy(alpha = alpha), Color.Transparent),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.width * 0.9f
                    )
                )
            }
    )
}

@Composable
private fun HomeTopBar(
    onNewChat: (() -> Unit)?,
    onOpenHistory: (() -> Unit)?,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SparkleMark(size = 24.dp, brush = monoGradient())
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.assistant_display_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        if (onNewChat != null) {
            IconButton(onClick = onNewChat) {
                Icon(VaIcons.Plus, contentDescription = "Новый чат", modifier = Modifier.size(24.dp))
            }
        }
        if (onOpenHistory != null) {
            IconButton(onClick = onOpenHistory) {
                Icon(VaIcons.History, contentDescription = "История запросов", modifier = Modifier.size(24.dp))
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(VaIcons.Sliders, contentDescription = "Настройки", modifier = Modifier.size(24.dp))
        }
    }
}

/** Главная «лента»: приветствие → статус агента → быстрые команды → недавние запросы. */
@Composable
private fun HomeFeed(
    phase: AgentPhase,
    statusText: String,
    running: Boolean,
    history: List<HistoryItem>,
    onPrompt: (String) -> Unit,
    onToggleAgent: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column {
                Text(
                    "Привет!",
                    style = MaterialTheme.typography.displaySmall.copy(brush = monoGradient())
                )
                Text(
                    stringResource(R.string.assistant_greeting),
                    style = MaterialTheme.typography.displaySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        item {
            AgentStatusCard(phase = phase, statusText = statusText, running = running, onToggle = onToggleAgent)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Быстрые команды")
                QUICK_PROMPTS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { prompt ->
                            QuickPromptCard(prompt, onClick = { onPrompt(prompt) }, modifier = Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Запросы за этот сеанс", modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) {
                            Text("Очистить", color = cs.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (history.isEmpty()) {
                    EmptyHistoryCard()
                } else {
                    HistoryList(history = history.take(6), onPick = onPrompt)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp)
    )
}

@Composable
private fun QuickPromptCard(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .border(1.dp, cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(14.dp)
            .height(64.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        SparkleMark(size = 16.dp, color = cs.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyHistoryCard() {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, cs.outlineVariant, shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(VaIcons.History, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            "Здесь появятся ваши запросы — голосовые и набранные.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
    }
}

/** Список запросов в одной скруглённой карточке с разделителями; тап — повторить запрос. */
@Composable
private fun HistoryList(history: List<HistoryItem>, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .border(1.dp, cs.outlineVariant, shape)
    ) {
        history.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = cs.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item.text) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(VaIcons.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.timestamp > 0) {
                        Text(
                            DateUtils.getRelativeTimeSpanString(
                                item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                            ).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    history: List<HistoryItem>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow,
        contentColor = cs.onSurface
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Запросы за этот сеанс", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Очистить", color = cs.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (history.isEmpty()) EmptyHistoryCard() else HistoryList(history = history, onPick = onPick)
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
}
