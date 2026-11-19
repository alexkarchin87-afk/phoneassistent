package com.voiceagent.oneplus13.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun LocalModelSection() {
    val context = LocalContext.current
    val manager = remember { LocalModelManager(context) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    val isLocal = remember(refresh) { manager.isLocal }
    val ready = remember(refresh) { manager.ready }
    val id = remember(refresh) { manager.downloadId }

    LaunchedEffect(id) {
        if (id == -1L) return@LaunchedEffect
        while (true) {
            when (val state = manager.downloadStatus()) {
                DownloadStatus.Idle -> break
                is DownloadStatus.Downloading -> status = state.percent?.let { "Загрузка: $it%." }
                    ?: "Ожидание загрузки. Нужен Wi-Fi."
                DownloadStatus.Failed -> { status = "Загрузка не удалась. Отмени её и попробуй ещё раз."; break }
                DownloadStatus.Downloaded -> {
                    verifying = true
                    status = "Проверяем целостность файла — это может занять несколько минут."
                    try {
                        manager.verifyDownload()
                        status = "Модель проверена. Теперь можно включить локальный режим."
                        refresh++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "Проверка не прошла. Отмени загрузку и попробуй снова." }
                    finally { verifying = false }
                    break
                }
            }
            delay(1200)
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (isLocal) "Сейчас: локальная модель на телефоне" else "Сейчас: облачные модели")
        Text("Qwen 2.5 1.5B Q4: файл 1,12 ГБ. Нужны Wi-Fi и не менее 1,5 ГБ свободного места. При работе расходуются память и батарея; ответы могут быть медленными и ошибочными.")
        Text(if (ready) "Модель скачана и проверена." else "Модель ещё не готова.")
        if (status.isNotBlank()) Text(status)
        if (!ready && id == -1L) {
            Button(onClick = {
                try { manager.startDownload(); status = "Загрузка добавлена в систему."; refresh++ }
                catch (_: Exception) { status = "Не удалось начать загрузку. Проверь свободное место и системные загрузки." }
            }) { Text("Скачать Qwen через Wi-Fi") }
        }
        if (id != -1L && !verifying) {
            OutlinedButton(onClick = { manager.cancelDownload(); status = "Загрузка отменена."; refresh++ }) {
                Text("Отменить загрузку")
            }
        }
        if (ready && !isLocal) {
            Button(onClick = { manager.useLocal(); refresh++ }) { Text("Использовать локальную модель") }
        }
        if (isLocal) {
            OutlinedButton(onClick = { manager.useCloud(); refresh++ }) { Text("Вернуться к облаку") }
        }
        Text("Модель для ответов работает без интернета. Голосовое распознавание и другие функции настраиваются отдельно; Telegram по-прежнему требует сеть. Для действий остаются подтверждения.")
    }
}
