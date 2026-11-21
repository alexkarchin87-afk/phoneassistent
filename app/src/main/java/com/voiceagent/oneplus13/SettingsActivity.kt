package com.voiceagent.oneplus13

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceagent.oneplus13.local.LocalModelSection
import com.voiceagent.oneplus13.core.BackupManager
import com.voiceagent.oneplus13.core.CrashLogger
import com.voiceagent.oneplus13.core.FeatureRequests
import com.voiceagent.oneplus13.core.UserMemory
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.system.CheckStatus
import com.voiceagent.oneplus13.system.Diagnostics
import com.voiceagent.oneplus13.system.RootActions
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.voiceagent.oneplus13.integrations.bot.TelegramBotService
import com.voiceagent.oneplus13.integrations.bot.TelegramBotState
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
        val ok = try { runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            BackupManager(this).importBackup(tmp)
        }.getOrDefault(false) } finally { tmp.delete() }
        importMessage = if (ok) {
            "Настройки импортированы. API-ключи и токены из файла не восстанавливаются — введи их вручную. Если агент был запущен, перезапусти его."
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
    val botState by TelegramBotState.state.collectAsState()
    val projectSecrets = remember { listProjectSecrets(context) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = "Ключи и диагностика", onBack = onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsColumn {
                SettingsGroup(title = "Диагностика", description = "Здесь видно состояние основных доступов и модели речи.") {
                    DiagnosticsSection()
                }
                SettingsGroup(title = "Root-доступ", description = "Проверка запрашивает root в Magisk или KernelSU и проверяет только его наличие. Другие разрешения Android включаются отдельно.") {
                    RootAccessSection()
                }

                SettingsGroup(title = "Мозг ассистента", description = "Выбор модели применяется к голосовому агенту и Telegram.") {
                    LocalModelSection()
                }

                    SettingsGroup(title = "Ключи ассистента", description = "Для облачных ответов нужен ключ DeepSeek API. Для локальной модели ключ не нужен.") {
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
                                        val newToken = telegramToken.trim()
                                        if (secrets.get(SecretsStore.KEY_TELEGRAM_BOT_TOKEN).orEmpty() != newToken) {
                                            context.stopService(Intent(context, TelegramBotService::class.java))
                                            secrets.remove(SecretsStore.KEY_TELEGRAM_OWNER_ID)
                                            TelegramBotState.update { it.copy(running = false, status = "Токен изменён. Запусти Telegram и подтверди чат заново.", candidateId = null, candidateName = null, approvedId = null) }
                                        }
                                        telegramToken = newToken
                                        secrets.put(SecretsStore.KEY_TELEGRAM_BOT_TOKEN, newToken)
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
                        description = "Сохрани токен бота, запусти Telegram, отправь боту /start и подтверди свой личный чат здесь. Для ответов используется выбранный мозг. Telegram требует интернет; управление телефоном из бота пока недоступно."
                    ) {
                        SettingsRow(
                            icon = VaIcons.Info,
                            title = "Состояние Telegram",
                            subtitle = botState.status
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = VaIcons.Power,
                            title = "Запустить Telegram",
                            onClick = {
                                if (secrets.get(SecretsStore.KEY_TELEGRAM_BOT_TOKEN).isNullOrBlank()) {
                                    TelegramBotState.update { it.copy(status = "Сначала сохрани токен Telegram.") }
                                } else {
                                    runCatching {
                                        ContextCompat.startForegroundService(context, Intent(context, TelegramBotService::class.java))
                                    }.onFailure { TelegramBotState.update { it.copy(status = "Не удалось запустить сервис Telegram.") } }
                                }
                            },
                            trailing = { ChevronTrailing() }
                        )
                        botState.candidateId?.let { candidateId ->
                            SettingsDivider()
                            SettingsRow(
                                icon = VaIcons.Check,
                                title = "Подтвердить личный чат",
                                subtitle = "${botState.candidateName.orEmpty()} · ID $candidateId. Проверь, что это твой аккаунт, и нажми здесь.",
                                onClick = {
                                    if (TelegramBotState.state.value.candidateId == candidateId && TelegramBotState.state.value.running) {
                                        secrets.put(SecretsStore.KEY_TELEGRAM_OWNER_ID, candidateId)
                                        TelegramBotState.update { it.copy(approvedId = candidateId, candidateId = null, candidateName = null, status = "Чат подтверждён. Напиши боту /start.") }
                                    }
                                },
                                trailing = { ChevronTrailing() }
                            )
                        }
                        botState.approvedId?.let { approvedId ->
                            SettingsDivider()
                            SettingsRow(
                                icon = VaIcons.Close,
                                title = "Отключить привязанный чат",
                                subtitle = "Подтверждён ID $approvedId",
                                onClick = {
                                    secrets.remove(SecretsStore.KEY_TELEGRAM_OWNER_ID)
                                    TelegramBotState.update { it.copy(approvedId = null, candidateId = null, candidateName = null, status = "Привязка удалена. Для новой отправь боту /start.") }
                                },
                                trailing = { ChevronTrailing() }
                            )
                        }
                        SettingsDivider()
                        SettingsRow(
                            icon = VaIcons.Power,
                            title = "Остановить Telegram",
                            onClick = {
                                context.stopService(Intent(context, TelegramBotService::class.java))
                                TelegramBotState.update { it.copy(running = false, status = "Остановлен", candidateId = null, candidateName = null) }
                            },
                            trailing = { ChevronTrailing() }
                        )
                    }

                    SettingsGroup(title = "Как отвечает Ваня") {
                        SettingsRow(
                            icon = VaIcons.Pulse,
                            title = "Быстро или вдумчиво",
                            subtitle = "Режим выбирается рядом со строкой ввода. Локальная Qwen 3.5 умеет рассуждать во вдумчивом режиме. " +
                                "В облаке быстрый режим использует DeepSeek-chat, вдумчивый — DeepSeek-reasoner и при наличии ключа может вызвать Claude; " +
                                "это может стоить дороже. Для генерации кода сложный облачный режим может включаться отдельно."
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
    var checks by remember { mutableStateOf<List<com.voiceagent.oneplus13.system.DiagnosticCheck>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        loading = true
        failure = null
        try { checks = withContext(Dispatchers.IO) { Diagnostics.runAll(context.applicationContext) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "Не удалось проверить доступы. Попробуй ещё раз." }
        finally { loading = false }
    }

    Column {
        if (loading) SettingsRow(icon = VaIcons.Pulse, title = "Проверяем настройки…")
        failure?.let { SettingsRow(icon = VaIcons.Alert, title = it) }
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
                        onClick = {
                            val intent = Intent(action).apply {
                                if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS || action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            }
                            runCatching { context.startActivity(intent) }.onFailure {
                                Toast.makeText(context, "Не удалось открыть системные настройки.", Toast.LENGTH_LONG).show()
                            }
                        },
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
            onClick = { if (!loading) refresh++ },
            trailing = { ChevronTrailing() }
        )
    }
}

@Composable
private fun RootAccessSection() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Нажми для проверки. При запросе разреши доступ в Magisk.") }
    SettingsRow(
        icon = VaIcons.Key,
        title = if (checking) "Проверяем root…" else "Проверить root-доступ",
        subtitle = status,
        onClick = {
            if (!checking) scope.launch {
                checking = true
                try {
                    status = when (val result = withContext(Dispatchers.IO) { RootActions.checkAccess() }) {
                        is RootActions.Result.Success -> "Root-доступ подтверждён для этой установки VoiceAgent."
                        is RootActions.Result.NoRoot -> "su не найден. Проверь Magisk и включён ли root."
                        is RootActions.Result.Error -> result.message
                    }
                } finally { checking = false }
            }
        },
        trailing = { ChevronTrailing() }
    )
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
            val clip = ClipData.newPlainText(label, value)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
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
