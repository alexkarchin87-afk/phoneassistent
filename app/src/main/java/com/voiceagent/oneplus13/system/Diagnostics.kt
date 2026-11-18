package com.voiceagent.oneplus13.system

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voiceagent.oneplus13.BuildConfig
import com.voiceagent.oneplus13.core.security.SecretsStore
import java.io.File

enum class CheckStatus { OK, WARNING, FAIL }

data class DiagnosticCheck(
    val title: String,
    val status: CheckStatus,
    val detail: String,
    /** Что открыть, чтобы починить — null, если чинить нечего (например, ключ нужно
     *  вписать в Настройки самому, а не через системный экран). */
    val fixSettingsAction: String? = null
)

/**
 * "Всё ли работает?" — самопроверка перед тем, как что-то не сработает молча.
 * Почти все реальные проблемы первого запуска VoiceAgent (не начала слушать,
 * не открывает приложения, не читает уведомления) — это не баг в коде, а одно
 * из системных разрешений или Vosk-модель, которую нужно добавить руками
 * (см. README) и о которой легко забыть. Раньше единственным признаком было
 * молчание в логах; здесь — явный чек-лист с понятной причиной на каждый пункт.
 */
object Diagnostics {

    fun runAll(context: Context): List<DiagnosticCheck> = listOf(
        checkMicrophone(context),
        checkNotificationsPermission(context),
        checkAccessibilityService(context),
        checkNotificationListener(context),
        checkOverlayPermission(context),
        checkBatteryOptimization(context),
        checkDeepSeekKey(context),
        checkVoskModel(context),
        checkRootAccess()
    )

    private fun checkMicrophone(context: Context): DiagnosticCheck {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return DiagnosticCheck(
            "Микрофон",
            if (granted) CheckStatus.OK else CheckStatus.FAIL,
            if (granted) "Разрешение выдано" else "Без этого разрешения агент вообще не услышит команды",
            fixSettingsAction = if (granted) null else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
    }

    private fun checkNotificationsPermission(context: Context): DiagnosticCheck {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return DiagnosticCheck("Уведомления (значок в шторке)", CheckStatus.OK, "Не требуется на этой версии Android")
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return DiagnosticCheck(
            "Уведомления (значок в шторке)",
            if (granted) CheckStatus.OK else CheckStatus.WARNING,
            if (granted) "Разрешение выдано" else "Без него не будет видно, что фоновый сервис вообще запущен",
            fixSettingsAction = if (granted) null else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
    }

    private fun checkAccessibilityService(context: Context): DiagnosticCheck {
        val enabled = isServiceEnabled(context, ComponentName(context, AccessibilityAgent::class.java).flattenToShortString())
        return DiagnosticCheck(
            "Спец. возможности (Accessibility)",
            if (enabled) CheckStatus.OK else CheckStatus.FAIL,
            if (enabled) "Включено" else "Без этого агент не сможет читать экран, нажимать кнопки и листать — " +
                "команды вроде «что на экране» и «открой Х» работать не будут",
            fixSettingsAction = if (enabled) null else Settings.ACTION_ACCESSIBILITY_SETTINGS
        )
    }

    private fun checkNotificationListener(context: Context): DiagnosticCheck {
        val enabled = isServiceEnabled(context, ComponentName(context, NotificationReader::class.java).flattenToShortString())
        return DiagnosticCheck(
            "Доступ к уведомлениям",
            if (enabled) CheckStatus.OK else CheckStatus.WARNING,
            if (enabled) "Включено" else "Без этого «что нового?»/«прочитай уведомления» и авто-подхват токена от " +
                "BotFather работать не будут — остальное не пострадает",
            fixSettingsAction = if (enabled) null else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        )
    }

    private fun checkOverlayPermission(context: Context): DiagnosticCheck {
        val granted = Settings.canDrawOverlays(context)
        return DiagnosticCheck(
            "Оверлей поверх других приложений",
            if (granted) CheckStatus.OK else CheckStatus.WARNING,
            if (granted) "Включено" else "Нужно только для плавающего пузыря-ассистента поверх других приложений — " +
                "без него сам голосовой агент всё равно работает",
            fixSettingsAction = if (granted) null else Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        )
    }

    private fun checkBatteryOptimization(context: Context): DiagnosticCheck {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            pm.isIgnoringBatteryOptimizations(context.packageName)
        return DiagnosticCheck(
            "Оптимизация батареи",
            if (ignoring) CheckStatus.OK else CheckStatus.WARNING,
            if (ignoring) "Отключена для VoiceAgent" else "OnePlus агрессивно убивает фоновые сервисы — агент может " +
                "переставать слушать через какое-то время после блокировки экрана",
            fixSettingsAction = if (ignoring) null else Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        )
    }

    private fun checkDeepSeekKey(context: Context): DiagnosticCheck {
        val key = SecretsStore(context).getOrDefault(SecretsStore.KEY_DEEPSEEK_API_KEY, BuildConfig.DEEPSEEK_API_KEY)
        val present = key.isNotBlank()
        return DiagnosticCheck(
            "Ключ DeepSeek API",
            if (present) CheckStatus.OK else CheckStatus.FAIL,
            if (present) "Задан" else "Без него агент не сможет ни понять команду, ни ответить — впиши ключ в " +
                "local.properties перед сборкой или в Настройках → «Ключи»",
            fixSettingsAction = null // это не системная настройка — чинится в самом приложении
        )
    }

    private fun checkVoskModel(context: Context): DiagnosticCheck {
        // STTEngine распаковывает assets/model-ru-small в filesDir/model при первом успешном запуске.
        val unpacked = File(context.filesDir, "model").let { it.exists() && it.list()?.isNotEmpty() == true }
        val present = unpacked || hasAssetModel(context)
        return DiagnosticCheck(
            "Оффлайн-модель распознавания речи (Vosk)",
            if (present) CheckStatus.OK else CheckStatus.FAIL,
            if (present) "Найдена" else "Модель не найдена в assets/model-ru-small — без неё голосовой ввод не " +
                "заработает вообще, скачай vosk-model-small-ru с alphacephei.com/vosk/models (см. README)",
            fixSettingsAction = null
        )
    }

    private fun hasAssetModel(context: Context): Boolean = runCatching {
        context.assets.list("model-ru-small")?.isNotEmpty() == true
    }.getOrDefault(false)

    private fun checkRootAccess(): DiagnosticCheck = DiagnosticCheck(
        "Root-доступ (KernelSU/Magisk)",
        CheckStatus.WARNING,
        "Проверяется при выполнении root-команды; для остальных функций не нужен.",
        fixSettingsAction = null
    )

    private fun isServiceEnabled(context: Context, flatComponentName: String): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            if (flatComponentName.contains("NotificationReader"))
                "enabled_notification_listeners"
            else
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(flatComponentName, ignoreCase = true) }
    }
}
