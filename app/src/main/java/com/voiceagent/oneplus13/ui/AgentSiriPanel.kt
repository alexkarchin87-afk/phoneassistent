package com.voiceagent.oneplus13.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voiceagent.oneplus13.R

/**
 * Панель чата агента.
 *
 * [dockedMode] = true — стационарная боковая колонка (планшет/DeX), без затемнения.
 * [dockedMode] = false — шторка снизу со скримом (оставлена для совместимости).
 * Тот же контент, что и в основном чате: [MessageList] + [Composer].
 */
@Composable
fun AgentSiriPanel(
    isVisible: Boolean,
    messages: List<ChatMessage>,
    quickPrompts: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onQuickPrompt: (String) -> Unit = {},
    dockedMode: Boolean = false,
    isListening: Boolean = false,
    maxWidth: Dp? = null
) {
    if (dockedMode) {
        AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            PanelContent(
                messages = messages,
                quickPrompts = quickPrompts,
                onSend = onSend,
                onQuickPrompt = onQuickPrompt,
                onDismiss = onDismiss,
                isListening = isListening,
                modifier = Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding(),
                showDragHandle = false
            )
        }
        return
    }

    AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(250))
            ) {
                val widthModifier = if (maxWidth != null) Modifier.widthIn(max = maxWidth).fillMaxWidth() else Modifier.fillMaxWidth()
                PanelContent(
                    messages = messages,
                    quickPrompts = quickPrompts,
                    onSend = onSend,
                    onQuickPrompt = onQuickPrompt,
                    onDismiss = onDismiss,
                    isListening = isListening,
                    modifier = widthModifier
                        .fillMaxHeight(0.75f)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .clickable(enabled = false) {},
                    showDragHandle = true
                )
            }
        }
    }
}

@Composable
private fun PanelContent(
    messages: List<ChatMessage>,
    quickPrompts: List<String>,
    onSend: (String) -> Unit,
    onQuickPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
    isListening: Boolean,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.background(cs.surfaceContainerLow)) {
        if (showDragHandle) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(cs.outline.copy(alpha = 0.5f))
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SparkIconPro(size = 34.dp, animated = isListening)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.assistant_display_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isListening) "слушаю…" else stringResource(R.string.assistant_role_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(VaIcons.Close, contentDescription = "Закрыть", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.assistant_greeting), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.assistant_greeting_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                }
            } else {
                MessageList(messages = messages, modifier = Modifier.fillMaxSize())
            }
        }

        if (quickPrompts.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickPrompts) { prompt -> PromptPill(prompt, onClick = { onQuickPrompt(prompt) }) }
            }
        }

        Composer(
            onSend = onSend,
            listening = isListening,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
        )
    }
}
