package com.voiceagent.oneplus13

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceagent.oneplus13.account.AccountManager
import com.voiceagent.oneplus13.core.BackupManager
import com.voiceagent.oneplus13.core.CrashLogger
import com.voiceagent.oneplus13.core.FeatureRequests
import com.voiceagent.oneplus13.core.UserMemory
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.system.CheckStatus
import com.voiceagent.oneplus13.system.Diagnostics
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.voiceagent.oneplus13.integrations.bot.TelegramBotService
import java.io.File

/**
 * Lets a non-developer change API keys without rebuilding the app, lists any bot
 * tokens VoiceAgent has automatically captured from Telegram (e.g. from BotFather)
 * for generated projects, what the agent remembers about the user, and a way to
 * export/import all of the above as one file when moving to a new phone.
 */
class SettingsActivity : ComponentActivity() {

    private var importMessage by mutableStateOf<String?>(null)

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val tmp = File(cacheDir, "voiceagent_import.json")
        val ok = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            BackupManager(this).importBackup(tmp)
        }.getOrDefault(false)
        importMessage = if (ok) {
            "Настройки импортированы. Если агент был запущен — перезапустите его, чтобы всё применилось."
        } else {
            "Не получилось прочитать файл — похоже, это не бэкап VoiceAgent."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    SettingsScreen(
                        importMessage = importMessage,
                        onExportBackup = {
                            val backup = BackupManager(this)
                            val file = backup.exportBackup()
                            startActivity(
                                Intent.createChooser(backup.shareIntent(file), "Экспорт настроек VoiceAgent")
                            )
                        },
                        onImportBackupClick = { importLauncher.launch("application/json") },
                        hasCrashLog = CrashLogger.hasAnyCrash(this),
                        onShareCrashLog = {
                            val file = CrashLogger.latestCrashFile(this) ?: return@SettingsScreen
                            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "Отправить лог краша"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    importMessage: String? = null,
    onExportBackup: () -> Unit = {},
    onImportBackupClick: () -> Unit = {},
    hasCrashLog: Boolean = false,
    onShareCrashLog: () -> Unit = {}
) {
    val context = LocalContext.current
    val secrets = remember { SecretsStore(context) }
    val role = remember { AccountManager(context).currentRole() }
    val isAdmin = role == AccountManager.Role.ADMIN

    var deepSeekKey by remember { mutableStateOf(secrets.get(SecretsStore.KEY_DEEPSEEK_API_KEY).orEmpty()) }
    var claudeKey by remember { mutableStateOf(secrets.get(SecretsStore.KEY_CLAUDE_API_KEY).orEmpty()) }
    var telegramToken by remember { mutableStateOf(secrets.get(SecretsStore.KEY_TELEGRAM_BOT_TOKEN).orEmpty()) }
    var maxToken by remember { mutableStateOf(secrets.get(SecretsStore.KEY_MAX_SERVICE_TOKEN).orEmpty()) }
    var botStatus by remember { mutableStateOf("") }
    val projectSecrets = remember { listProjectSecrets(context) }

    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item { Text("Диагностика", style = MaterialTheme.typography.titleMedium) }
        item { DiagnosticsSection() }

        if (!isAdmin) {
            item {
                Text(
                    "Обычный доступ: общие ключи API и резервная копия видны только тому " +
                        "аккаунту, который первым настраивал этот телефон.",
                    color = Color.Gray
                )
            }
        }

        if (isAdmin) {
            item { Text("Ключи ассистента", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    value = deepSeekKey,
                    onValueChange = { deepSeekKey = it },
                    label = { Text("DeepSeek API key") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = claudeKey,
                    onValueChange = { claudeKey = it },
                    label = { Text("Claude (Anthropic) API key") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = telegramToken,
                    onValueChange = { telegramToken = it },
                    label = { Text("Telegram-бот ассистента (токен)") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = maxToken,
                    onValueChange = { maxToken = it },
                    label = { Text("MAX service token") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            item {
                Button(
                    onClick = {
                        secrets.put(SecretsStore.KEY_DEEPSEEK_API_KEY, deepSeekKey)
                        secrets.put(SecretsStore.KEY_CLAUDE_API_KEY, claudeKey)
                        secrets.put(SecretsStore.KEY_TELEGRAM_BOT_TOKEN, telegramToken)
                        secrets.put(SecretsStore.KEY_MAX_SERVICE_TOKEN, maxToken)
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) { Text("Сохранить") }
            }
            item {
                Text("Telegram: сначала сохрани токен, затем запусти приём. MAX пока не подключён.")
                Button(onClick = {
                    if (secrets.get(SecretsStore.KEY_TELEGRAM_BOT_TOKEN).isNullOrBlank()) {
                        botStatus = "Сначала сохрани токен Telegram."
                    } else {
                        botStatus = runCatching {
                            ContextCompat.startForegroundService(context, Intent(context, TelegramBotService::class.java))
                            "Запрос на запуск отправлен; проверь уведомление сервиса."
                        }.getOrElse { "Не удалось запустить сервис: ${it.message}" }
                    }
                }) { Text("Запустить Telegram") }
                Button(onClick = {
                    context.stopService(Intent(context, TelegramBotService::class.java))
                    botStatus = "Сервис остановлен."
                }) { Text("Остановить Telegram") }
                if (botStatus.isNotBlank()) Text(botStatus)
            }

            item { Text("Как отвечает Ваня", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Выбора модели больше нет — Ваня сам решает по сложности " +
                        "запроса. Простое — сразу через DeepSeek. Сложное " +
                        "(код, объяснения, многошаговые задачи) — сначала " +
                        "черновик от DeepSeek-R1, а если выше указан ключ " +
                        "Claude — его проверяет и правит Claude, и уже " +
                        "исправленный ответ идёт тебе. Без ключа Claude в " +
                        "сложных случаях просто отвечает один R1.",
                    color = Color.Gray
                )
            }

            item { Text("Токены, найденные для сгенерированных проектов", style = MaterialTheme.typography.titleMedium) }
            if (projectSecrets.isEmpty()) {
                item { Text("Пока ничего не найдено") }
            } else {
                items(projectSecrets) { (label, value) ->
                    ProjectSecretRow(label, value, context)
                }
            }
        }

        item { Text("Что помню о тебе", style = MaterialTheme.typography.titleMedium) }
        item { MemorySection() }

        item { Text("Запросы на новые команды", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Утилиты, которые попросили \"пришить к Ване как команду\". Сам агент код " +
                    "себе не вшивает — это очередь на следующий раз, когда возьмётесь " +
                    "пересобирать приложение (например, через Claude Code).",
                color = Color.Gray
            )
        }
        item { FeatureRequestsSection() }

        if (isAdmin) {
            item { Text("Резервная копия", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Файл настроек и сохранённых фактов для переноса на новый телефон. " +
                        "Ключи и токены не экспортируются: их нужно ввести заново. " +
                        "Файл не зашифрован и может содержать личные сведения.",
                    color = Color.Gray
                )
            }
            item {
                Column {
                    Button(onClick = onExportBackup, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("Экспортировать настройки")
                    }
                    Button(onClick = onImportBackupClick, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("Импортировать настройки")
                    }
                    importMessage?.let { Text(it, color = Color.Gray) }
                }
            }
        }

        item { Text("Если Ваня упал", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                if (hasCrashLog) {
                    "Есть сохранённый лог последнего краша — отправь его тому, кто " +
                        "дорабатывает Ваню (например, в чат с Claude), вместе с описанием " +
                        "\"не запускается\" или что именно сломалось. По стектрейсу баг " +
                        "чинится по конкретной строке кода, а не вслепую."
                } else {
                    "Крашей пока не было — если что-то упадёт, лог появится здесь сам."
                },
                color = Color.Gray
            )
        }
        if (hasCrashLog) {
            item {
                Button(onClick = onShareCrashLog, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Отправить лог последнего краша")
                }
            }
        }
    }
}

/** Простой список фактов, которые агент запомнил из разговоров ("запомни,
 *  что я Лёша и мне 13") — с возможностью удалить конкретный факт или
 *  очистить всё разом. [навсегда] — сказано "запомни навсегда", убрать можно
 *  только явным "забудь"; остальное — обычные факты, которые сами сжимаются
 *  при накоплении. Хранится только на телефоне, см. core/UserMemory.kt. */
@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(Diagnostics.runAll(context)) }

    Column {
        checks.forEach { check ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                val (icon, color) = when (check.status) {
                    CheckStatus.OK -> "✓" to Color(0xFF2E7D32)
                    CheckStatus.WARNING -> "!" to Color(0xFFF9A825)
                    CheckStatus.FAIL -> "✗" to Color(0xFFC62828)
                }
                Text("$icon ${check.title}", color = color, fontWeight = FontWeight.Medium)
                Text(check.detail, color = Color.Gray)
                check.fixSettingsAction?.let { action ->
                    Button(onClick = { context.startActivity(Intent(action)) }) { Text("Открыть настройки") }
                }
            }
        }
        Button(onClick = { checks = Diagnostics.runAll(context) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Проверить заново")
        }
    }
}

@Composable
private fun MemorySection() {
    val context = LocalContext.current
    val memory = remember { UserMemory(context) }
    var facts by remember { mutableStateOf(memory.allDetailed()) }

    if (facts.isEmpty()) {
        Text("Пока ничего — скажи агенту \"запомни, что ...\"")
        return
    }

    Column {
        facts.forEach { fact ->
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                val tag = if (fact.tier == com.voiceagent.oneplus13.core.MemoryTier.P1) "[навсегда] " else ""
                Text(tag + fact.text)
                Button(onClick = {
                    memory.forget(fact.text)
                    facts = memory.allDetailed()
                }) { Text("Забыть") }
            }
        }
        Button(onClick = {
            memory.clear()
            facts = memory.allDetailed()
        }) { Text("Забыть всё") }
    }
}

/** Очередь "пришей как команду" — см. core/FeatureRequests.kt. Только просмотр
 *  и удаление; сам агент никогда не пишет отсюда код обратно в себя. */
@Composable
private fun FeatureRequestsSection() {
    val context = LocalContext.current
    val store = remember { FeatureRequests(context) }
    var requests by remember { mutableStateOf(store.all()) }

    if (requests.isEmpty()) {
        Text("Пока пусто")
        return
    }

    Column {
        requests.forEach { req ->
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(req.description)
                Button(onClick = {
                    store.remove(req.projectName)
                    requests = store.all()
                }) { Text("Убрать из очереди") }
            }
        }
    }
}

@Composable
private fun ProjectSecretRow(label: String, value: String, context: Context) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        }) { Text("Скопировать") }
    }
}

/** SecretsStore doesn't expose key enumeration (EncryptedSharedPreferences discourages
 *  it), so project-secret keys are tracked separately here for display purposes. */
private fun listProjectSecrets(context: Context): List<Pair<String, String>> {
    val store = SecretsStore(context)
    return store.projectSecretKeys().mapNotNull { key -> store.get(key)?.let { key to it } }
}

