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
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LocalModelSection() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember { LocalModelManager(context) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var performance by remember { mutableStateOf(LocalPerformance.read(context)) }
    var armEnabled by remember { mutableStateOf(LocalPerformance.armEnabled(context)) }
    val lastRun by LocalInference.lastRun.collectAsState()
    var verifying by remember { mutableStateOf(false) }
    val isLocal = remember(refresh) { manager.isLocal }
    val ready = remember(refresh) { manager.downloadedReady }
    val recommendedReady = remember(refresh) { manager.recommendedReady }
    val recommendedId = remember(refresh) { manager.recommendedDownloadId }
    var recommendedStatus by remember { mutableStateOf("") }
    var recommendedVerifying by remember { mutableStateOf(false) }
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

    LaunchedEffect(id, lifecycle) {
        if (id == -1L) return@LaunchedEffect
        while (true) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            when (val state = withContext(Dispatchers.IO) { manager.downloadStatus() }) {
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
            delay(3000)
        }
    }
    LaunchedEffect(recommendedId, lifecycle) {
        if (recommendedId == -1L) return@LaunchedEffect
        while (true) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            when (val state = withContext(Dispatchers.IO) { manager.recommendedDownloadStatus() }) {
                DownloadStatus.Idle -> break
                is DownloadStatus.Downloading -> recommendedStatus = state.percent?.let { "Загрузка Qwen 3.5: $it%." } ?: "Ожидание Wi-Fi."
                DownloadStatus.Failed -> { recommendedStatus = "Загрузка не удалась. Отмени её и попробуй снова."; break }
                DownloadStatus.Downloaded -> {
                    recommendedVerifying = true
                    recommendedStatus = "Проверяем загруженный файл…"
                    try {
                        manager.verifyRecommendedDownload()
                        manager.selectRecommended()
                        recommendedStatus = "Qwen 3.5 проверена и выбрана."
                        refresh++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { recommendedStatus = "Проверка не прошла. Отмени загрузку и попробуй снова." }
                    finally { recommendedVerifying = false }
                    break
                }
            }
            delay(3000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Нагрузка локальной модели: ${performance.label}")
        Text("Прохладнее — меньше потоков, возможен более долгий ответ. Быстрее — больше нагрузка. При нагреве система может попросить снизить нагрузку. Выбор применяется к следующему запросу. Параметры рассуждения не меняются; эффект зависит от телефона.")
        LocalPerformance.entries.forEach { profile ->
            OutlinedButton(onClick = { LocalPerformance.save(context, profile); performance = profile }) {
                Text(if (profile == performance) "✓ ${profile.label}" else profile.label)
            }
        }
        if (LocalPerformance.armSupported()) {
            Text("ARM-ускорение (экспериментально)")
            Switch(checked = armEnabled, onCheckedChange = { enabled ->
                LocalPerformance.setArmEnabled(context, enabled); armEnabled = enabled
            })
            Text("Попробовать оптимизированные ARM-вычисления для поддерживаемых слоёв. Первая загрузка модели может стать дольше и потребовать больше памяти; польза зависит от телефона. При проблемах выключи и повтори запрос. Изменение применяется к следующему запросу.")
        }
        lastRun?.let { run ->
            Text("Последний локальный запрос: загрузка ${run.loadMs / 1000.0} с; обработка запроса ${run.promptMs / 1000.0} с; генерация ${run.generationMs / 1000.0} с, токенов ${run.tokens}. ${if (run.cached) "Модель уже была загружена." else "Модель загружалась заново."} ${if (run.armEnabled) "Выбран режим ARM; поддержка операций зависит от модели и телефона." else "Обычный режим."}")
            Text("Время CPU всего приложения за этот запрос — ${run.processCpuMs} мс. Несколько ядер могут работать одновременно.")
            Text("Токены включают скрытое рассуждение. Для сравнения используй одинаковый запрос, модель и режим; телефон и температура влияют на время.")
        }
        Text(if (isLocal) "Сейчас: $selectedName" else "Сейчас: облачные модели")
        Text("Рекомендуемая Qwen 3.5 4B: 3,03 ГБ. Работает на телефоне и поддерживает быстрый и вдумчивый режимы. Скачать по Wi-Fi; нужно не менее 3,5 ГБ свободного места.")
        if (recommendedStatus.isNotBlank()) Text(recommendedStatus)
        if (!recommendedReady && recommendedId == -1L) {
            Button(onClick = {
                try { manager.startRecommendedDownload(); recommendedStatus = "Загрузка добавлена в систему."; refresh++ }
                catch (_: Exception) { recommendedStatus = "Не удалось начать загрузку. Проверь место и системные загрузки." }
            }) { Text("Скачать Qwen 3.5 4B") }
        }
        if (recommendedId != -1L && !recommendedVerifying) {
            OutlinedButton(onClick = { manager.cancelRecommendedDownload(); recommendedStatus = "Загрузка отменена."; refresh++ }) { Text("Отменить загрузку Qwen 3.5") }
        }
        if (recommendedReady && (!isLocal || selectedName != "Qwen 3.5 4B")) {
            Button(onClick = { manager.selectRecommended(); refresh++ }) { Text("Использовать Qwen 3.5 4B") }
        }
        Text("Выбери уже скачанный одиночный файл GGUF. Приложение попробует читать его без копирования; если файл лежит внутри другого приложения, сначала экспортируй его в общую папку.")
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Выбрать свою модель GGUF") }
        Text("Режим выбирается рядом с полем ввода. Для Qwen 3.5 вдумчивый режим включает рассуждение; для других моделей результат зависит от их возможностей. В облаке вдумчивый режим может увеличить расходы API.")
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
