package com.voiceagent.oneplus13.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.voiceagent.oneplus13.R

/**
 * Компактная карточка-попап: используется и в Windows-режиме приложения, и в системном
 * оверлее поверх других приложений. Без тени (окно WindowManager её обрезает) — вместо
 * неё тонкий кант и скругление 28dp в стиле Gemini.
 */
@Composable
fun WindowsCornerPopup(
    isVisible: Boolean,
    isListening: Boolean,
    messages: List<ChatMessage>,
    quickPrompts: List<String>,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 340.dp
) {
    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180)),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.94f, animationSpec = tween(150)),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.width(width),
            shape = RoundedCornerShape(28.dp),
            color = cs.surfaceContainerLow,
            contentColor = cs.onSurface,
            border = BorderStroke(1.dp, cs.outlineVariant)
        ) {
            Column(Modifier.padding(bottom = 12.dp)) {
                PopupHeader(isListening = isListening, onDismiss = onDismiss)

                if (messages.isEmpty()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.assistant_greeting), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.assistant_greeting_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant
                        )
                    }
                } else {
                    MessageList(
                        messages = messages,
                        compact = true,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                    )
                }

                if (quickPrompts.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(quickPrompts) { prompt -> PromptPill(prompt, onClick = { onQuickPrompt(prompt) }) }
                    }
                }

                Composer(
                    onSend = onSend,
                    listening = isListening,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun PopupHeader(isListening: Boolean, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SparkIconPro(size = 32.dp, animated = isListening)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.assistant_display_name), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isListening) cs.onSurface else cs.outline)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isListening) "слушаю" else stringResource(R.string.assistant_ready_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(VaIcons.Close, contentDescription = "Закрыть", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
