package com.voiceagent.oneplus13.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LocalModelSection() {
    val context = LocalContext.current
    val manager = remember { LocalModelManager(context) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    val isLocal = remember(refresh) { manager.isLocal }
    val ready = remember(refresh) { manager.downloadedReady }
    val imported = remember(refresh) { manager.importedModels() }
    val selectedUri = remember(refresh) { manager.selectedUri }
    val selectedName = remember(refresh) { manager.selectedName }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val model = manager.importModel(uri)
                manager.selectImported(model)
                status = "Выбрана ${model.name}. Файл остаётся на прежнем месте."
                refresh++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                status = when {
                    error.message.orEmpty().contains("LOCAL_SHARDED_MODEL") -> "Модели из нескольких частей пока не поддерживаются. Выбери один файл GGUF."
                    error.message.orEmpty().contains("LOCAL_NOT_GGUF") -> "Выбранный файл не похож на GGUF."
                    else -> "Не удалось открыть файл. Выбери доступный локальный GGUF в проводнике."
                }
            }
        }
    }
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
        Text(if (isLocal) "Сейчас: $selectedName" else "Сейчас: облачные модели")
        Text("Выбери уже скачанный одиночный файл GGUF. Приложение попробует читать его без копирования; если файл лежит внутри другого приложения, сначала экспортируй его в общую папку.")
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Выбрать свою модель GGUF") }
        Text("Модели 14B и 27B могут занимать более 10 ГБ памяти и долго отвечать. Скорость и доступная память зависят от телефона. Файлы не скачиваются повторно.")
        imported.forEach { model ->
            OutlinedButton(onClick = { manager.selectImported(model); status = "Выбрана ${model.name}."; refresh++ }) {
                Text(if (isLocal && selectedUri == model.uri) "Выбрана: ${model.name}" else "Использовать: ${model.name}")
            }
        }
        Text("Qwen 2.5 1.5B Q4: файл 1,12 ГБ. Нужны Wi-Fi и не менее 1,5 ГБ свободного места. При работе расходуются память и батарея; ответы могут быть медленными и ошибочными.")
        Text(if (ready) "Маленькая Qwen скачана и проверена." else "Маленькая Qwen ещё не скачана.")
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
        if (ready && (!isLocal || selectedUri != null)) {
            Button(onClick = { manager.selectDownloaded(); refresh++ }) { Text("Использовать Qwen 2.5 1.5B") }
        }
        if (isLocal) {
            OutlinedButton(onClick = { manager.useCloud(); refresh++ }) { Text("Вернуться к облаку") }
        }
        Text("Модель для ответов работает без интернета. Голосовое распознавание и другие функции настраиваются отдельно; Telegram по-прежнему требует сеть. Для действий остаются подтверждения.")
    }
}
