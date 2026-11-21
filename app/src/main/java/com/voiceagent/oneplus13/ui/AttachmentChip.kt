package com.voiceagent.oneplus13.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Карточка готового .zip внутри чата — ровно то, о чём просили: сгенерированный
 * проект не нужно искать в Загрузках вручную, тут же можно открыть или
 * переслать (в Telegram, Termux и т.п.), как с файлом от Claude.
 * Используется и в [AgentSiriPanel], и в [WindowsCornerPopup] (а значит и в
 * системном оверлее — он переиспользует тот же composable).
 */
@Composable
fun AttachmentChip(label: String, path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant, shape)
            .clickable { openZipFile(context, path) }
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(VaIcons.File, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = { shareZipFile(context, path) }, modifier = Modifier.size(32.dp)) {
            Icon(VaIcons.Share, contentDescription = "Поделиться", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Открыть zip тем, что установлено в системе (обычно файловый менеджер
 *  умеет заглянуть внутрь). Если открыть нечем — сразу падаем в "Поделиться". */
fun openZipFile(context: Context, path: String) {
    val uri = zipUri(context, path) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/zip")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { shareZipFile(context, path) }
}

/** Системная шторка "Поделиться" — отправить архив в Telegram, Termux,
 *  почту и т.п. Работает и из Activity, и из Service (оверлей-пузырь). */
fun shareZipFile(context: Context, path: String) {
    val uri = zipUri(context, path) ?: return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "Поделиться проектом").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

private fun zipUri(context: Context, path: String) = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
}.getOrNull()
