package com.voiceagent.oneplus13

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.voiceagent.oneplus13.ui.ChevronTrailing
import com.voiceagent.oneplus13.ui.SettingsColumn
import com.voiceagent.oneplus13.ui.SettingsDivider
import com.voiceagent.oneplus13.ui.SettingsGroup
import com.voiceagent.oneplus13.ui.SettingsRow
import com.voiceagent.oneplus13.ui.SettingsTopBar
import com.voiceagent.oneplus13.ui.VaIcons
import com.voiceagent.oneplus13.ui.VaSwitch
import com.voiceagent.oneplus13.ui.theme.VoiceAgentTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        enableEdgeToEdge()
        setContent {
            VoiceAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        onBack = { finish() },
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
    onShareCrashLog: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val secrets = remember { SecretsStore(context) }

    var deepSeekKey by remember { mutableStateOf(secrets.get(SecretsStore.KEY_DEEPSEEK_API_KEY).orEmpty()) }
    var claudeKey by remember { mutableStateOf(secrets.get(SecretsStore.KEY_CLAUDE_API_KEY).orEmpty()) }
    var telegramToken by remember { mutableStateOf(secrets.get(SecretsStore.KEY_TELEGRAM_BOT_TOKEN).orEmpty()) }
    var maxToken by remember { mutableStateOf(secrets.get(SecretsStore.KEY_MAX_SERVICE_TOKEN).orEmpty()) }
    var revealKeys by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var botStatus by remember { mutableStateOf("") }
    val projectSecrets = remember { listProjectSecrets(context) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = "Ключи и диагностика", onBack = onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsColumn {
                SettingsGroup(title = "Диагностика", description = "Почти все проблемы первого запуска — это разрешение или модель Vosk. Здесь видно, что именно не готово.") {
                    DiagnosticsSection()
                }

                    SettingsGroup(title = "Ключи ассистента", description = "Для ответов ИИ нужен ключ DeepSeek API. После сохранения перезапусти агента.") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            KeyField("DeepSeek API key", deepSeekKey, revealKeys) { deepSeekKey = it; saved = false }
                            KeyField("Claude (Anthropic) API key", claudeKey, revealKeys) { claudeKey = it; saved = false }
                            KeyField("Telegram-бот ассистента (токен)", telegramToken, revealKeys) { telegramToken = it; saved = false }
                            KeyField("MAX service token", maxToken, revealKeys) { maxToken = it; saved = false }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        secrets.put(SecretsStore.KEY_DEEPSEEK_API_KEY, deepSeekKey)
                                        secrets.put(SecretsStore.KEY_CLAUDE_API_KEY, claudeKey)
                                        secrets.put(SecretsStore.KEY_TELEGRAM_BOT_TOKEN, telegramToken)
                                        secrets.put(SecretsStore.KEY_MAX_SERVICE_TOKEN, maxToken)
                                        saved = true
                                    },
                                    shape = RoundedCornerShape(50)
                                ) { Text(if (saved) "Сохранено" else "Сохранить") }
                                Spacer(Modifier.size(12.dp))
                                OutlinedButton(onClick = { revealKeys = !revealKeys }, shape = RoundedCornerShape(50)) {
                                    Text(if (revealKeys) "Скрыть" else "Показать")
                                }
                            }
                        }
                    }

                    SettingsGroup(
                        title = "Telegram",
                        description = "Сначала сохрани токен. Приём включается вручную; MAX пока не подключён."
                    ) {
                        SettingsRow(
                            icon = VaIcons.Power,
                            title = "Запустить Telegram",
                            subtitle = botStatus.takeIf { it.isNotBlank() },
                            onClick = {
                                botStatus = if (secrets.get(SecretsStore.KEY_TELEGRAM_BOT_TOKEN).isNullOrBlank()) {
                                    "Сначала сохрани токен Telegram."
                                } else {
                                    runCatching {
                                        ContextCompat.startForegroundService(
                                            context, Intent(context, TelegramBotService::class.java)
                                        )
                                        "Запрос на запуск отправлен; проверь уведомление сервиса."
                                    }.getOrElse { "Не удалось запустить сервис." }
                                }
                            },
                            trailing = { ChevronTrailing() }
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = VaIcons.Power,
                            title = "Остановить Telegram",
                            onClick = {
                                context.stopService(Intent(context, TelegramBotService::class.java))
                                botStatus = "Сервис остановлен."
                            },
                            trailing = { ChevronTrailing() }
                        )
                    }

                    SettingsGroup(title = "Как отвечает Ваня") {
                        SettingsRow(
                            icon = VaIcons.Pulse,
                            title = "Выбор модели — автоматический",
                            subtitle = "Простое — сразу через DeepSeek. Сложное (код, объяснения, многошаговые задачи) — " +
                                "сначала черновик от DeepSeek-R1, а если указан ключ Claude — его проверяет и правит Claude, " +
                                "и уже исправленный ответ идёт тебе. Без ключа Claude в сложных случаях отвечает один R1."
                        )
                    }

                    SettingsGroup(title = "Токены для сгенерированных проектов") {
                        if (projectSecrets.isEmpty()) {
                            SettingsRow(icon = VaIcons.Key, title = "Пока ничего не найдено")
                        } else {
                            projectSecrets.forEachIndexed { index, (label, value) ->
                                if (index > 0) SettingsDivider()
                                ProjectSecretRow(label, value, context)
                            }
                        }
                    }

                SettingsGroup(title = "Что помню о тебе") { MemorySection() }

                SettingsGroup(
                    title = "Запросы на новые команды",
                    description = "Утилиты, которые попросили «пришить к Ване как команду». Сам агент код себе не вшивает — " +
                        "это очередь на следующую пересборку приложения (например, через Claude Code)."
                ) { FeatureRequestsSection() }

                    SettingsGroup(
                        title = "Резервная копия",
                        description = "Файл настроек и сохранённых фактов для переноса на новый телефон. " +
                            "Ключи и токены не экспортируются: их нужно ввести заново. " +
                            "Файл не зашифрован и может содержать личные сведения."
                    ) {
                        SettingsRow(
                            icon = VaIcons.Upload,
                            title = "Экспортировать настройки",
                            onClick = onExportBackup,
                            trailing = { ChevronTrailing() }
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = VaIcons.Download,
                            title = "Импортировать настройки",
                            subtitle = importMessage,
                            onClick = onImportBackupClick,
                            trailing = { ChevronTrailing() }
                        )
                    }

                SettingsGroup(title = "Если Ваня упал") {
                    SettingsRow(
                        icon = VaIcons.Alert,
                        title = if (hasCrashLog) "Есть лог последнего краша" else "Крашей пока не было",
                        subtitle = if (hasCrashLog) {
                            "Отправь его тому, кто дорабатывает Ваню (например, в чат с Claude), вместе с описанием, " +
                                "что именно сломалось. По стектрейсу баг чинится по конкретной строке кода."
                        } else {
                            "Если что-то упадёт, лог появится здесь сам."
                        },
                        onClick = if (hasCrashLog) onShareCrashLog else null,
                        trailing = if (hasCrashLog) ({ ChevronTrailing() }) else null
                    )
                }

                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun KeyField(label: String, value: String, reveal: Boolean, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.onSurface,
            unfocusedBorderColor = cs.outlineVariant,
            focusedLabelColor = cs.onSurface,
            unfocusedLabelColor = cs.onSurfaceVariant,
            cursorColor = cs.onSurface
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<com.voiceagent.oneplus13.system.DiagnosticCheck>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        checks = withContext(Dispatchers.IO) { Diagnostics.runAll(context.applicationContext) }
        loading = false
    }

    Column {
        if (loading) SettingsRow(icon = VaIcons.Pulse, title = "Проверяем настройки…")
        checks.forEachIndexed { index, check ->
            if (index > 0) SettingsDivider()
            val icon: ImageVector = when (check.status) {
                CheckStatus.OK -> VaIcons.Check
                CheckStatus.WARNING -> VaIcons.Alert
                CheckStatus.FAIL -> VaIcons.Close
            }
            val action = check.fixSettingsAction
            SettingsRow(
                icon = icon,
                title = check.title,
                subtitle = check.detail,
                extra = if (action != null) ({
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(action)) },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (check.status == CheckStatus.FAIL) cs.error else cs.onSurface
                        )
                    ) { Text("Открыть настройки") }
                }) else null
            )
        }
        SettingsDivider()
        SettingsRow(
            icon = VaIcons.Pulse,
            title = "Проверить заново",
            onClick = {
                if (!loading) scope.launch {
                    loading = true
                    try { checks = withContext(Dispatchers.IO) { Diagnostics.runAll(context.applicationContext) } }
                    finally { loading = false }
                }
            },
            trailing = { ChevronTrailing() }
        )
    }
}

/** Факты, которые агент запомнил из разговоров («запомни, что я Лёша и мне 13») —
 *  можно забыть конкретный факт или всё сразу. [навсегда] — сказано «запомни навсегда». */
@Composable
private fun MemorySection() {
    val context = LocalContext.current
    val memory = remember { UserMemory(context) }
    var facts by remember { mutableStateOf(memory.allDetailed()) }

    if (facts.isEmpty()) {
        SettingsRow(icon = VaIcons.Bookmark, title = "Пока ничего", subtitle = "Скажи агенту «запомни, что …»")
        return
    }

    Column {
        facts.forEachIndexed { index, fact ->
            if (index > 0) SettingsDivider()
            val tag = if (fact.tier == com.voiceagent.oneplus13.core.MemoryTier.P1) "навсегда" else null
            SettingsRow(
                icon = VaIcons.Bookmark,
                title = fact.text,
                subtitle = tag,
                trailing = {
                    IconButton(onClick = {
                        memory.forget(fact.text)
                        facts = memory.allDetailed()
                    }) { Icon(VaIcons.Trash, contentDescription = "Забыть", modifier = Modifier.size(20.dp)) }
                }
            )
        }
        SettingsDivider()
        SettingsRow(
            icon = VaIcons.Trash,
            title = "Забыть всё",
            onClick = {
                memory.clear()
                facts = memory.allDetailed()
            }
        )
    }
}

/** Очередь «пришей как команду» — только просмотр и удаление. */
@Composable
private fun FeatureRequestsSection() {
    val context = LocalContext.current
    val store = remember { FeatureRequests(context) }
    var requests by remember { mutableStateOf(store.all()) }

    if (requests.isEmpty()) {
        SettingsRow(icon = VaIcons.Flow, title = "Пока пусто")
        return
    }

    Column {
        requests.forEachIndexed { index, req ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                icon = VaIcons.Flow,
                title = req.description,
                trailing = {
                    IconButton(onClick = {
                        store.remove(req.projectName)
                        requests = store.all()
                    }) { Icon(VaIcons.Close, contentDescription = "Убрать из очереди", modifier = Modifier.size(20.dp)) }
                }
            )
        }
    }
}

@Composable
private fun ProjectSecretRow(label: String, value: String, context: Context) {
    SettingsRow(
        icon = VaIcons.Key,
        title = label,
        subtitle = "Нажмите, чтобы скопировать",
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        },
        trailing = { Icon(VaIcons.Copy, contentDescription = "Скопировать", modifier = Modifier.size(20.dp)) }
    )
}

/** SecretsStore doesn't expose key enumeration (EncryptedSharedPreferences discourages
 *  it), so project-secret keys are tracked separately here for display purposes. */
private fun listProjectSecrets(context: Context): List<Pair<String, String>> {
    val store = SecretsStore(context)
    return store.projectSecretKeys().mapNotNull { key -> store.get(key)?.let { key to it } }
}
