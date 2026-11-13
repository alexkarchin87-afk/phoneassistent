package com.voiceagent.oneplus13.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Facade over device-level actions the agent can take: launching apps, adjusting
 * media volume, delegating on-screen interaction to [AccessibilityAgent], and
 * reading what's currently playing via [MediaSessionReader].
 * Kept separate from AgentOrchestrator so the LLM-facing "action vocabulary" maps
 * 1:1 onto simple, auditable methods here.
 */
class SystemController(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSessionReader = MediaSessionReader(context)

    /**
     * Принимает и точный package name ("com.spotify.music"), и обычное имя
     * приложения ("Spotify", "спотифай") — на случай, если LLM (голосовая
     * команда или ИИ-сборщик сценария) не уверена в точном пакете. Сначала
     * пробует как есть; если это не пакет, ищет среди установленных
     * приложений совпадение по видимому названию (без учёта регистра,
     * частичное). Запрос ACTION_MAIN/CATEGORY_LAUNCHER не подпадает под
     * ограничения видимости пакетов (Android 11+) — отдельное разрешение
     * QUERY_ALL_PACKAGES не нужно.
     */
    fun openApp(nameOrPackage: String): Boolean {
        val exact = context.packageManager.getLaunchIntentForPackage(nameOrPackage)
        val intent = exact ?: resolveByLabel(nameOrPackage)?.let {
            context.packageManager.getLaunchIntentForPackage(it)
        }
        intent ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun resolveByLabel(query: String): String? {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        val candidates = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val normalizedQuery = query.trim().lowercase()

        // Сначала точное совпадение по названию, потом — частичное, чтобы
        // "Карты" не подтягивало случайно "Автокарты" раньше настоящих "Google Карты".
        val exactLabelMatch = candidates.firstOrNull {
            it.loadLabel(pm).toString().lowercase() == normalizedQuery
        }
        val partialLabelMatch = candidates.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(normalizedQuery)
        }
        return (exactLabelMatch ?: partialLabelMatch)?.activityInfo?.packageName
    }

    fun openUrl(url: String) {
        val uri = Uri.parse(url)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Only http and https links are supported"
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun setMediaVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * percent.coerceIn(0, 100) / 100.0).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    }

    fun tapByText(label: String): Boolean = AccessibilityAgent.instance?.tapByText(label) ?: false

    fun readScreen(): List<String> = AccessibilityAgent.instance?.dumpVisibleText() ?: emptyList()

    fun swipeUp() {
        AccessibilityAgent.instance?.swipe(x1 = 540f, y1 = 1600f, x2 = 540f, y2 = 400f)
    }

    /** What's currently playing, from any app with an active media session — null if
     *  nothing is playing or Notification Access isn't granted yet (see [MediaSessionReader]). */
    fun nowPlaying(): MediaSessionReader.NowPlaying? = mediaSessionReader.currentTrack()

    /**
     * Brings Yandex Music to the foreground and taps its "like"/favorite control for
     * whatever is currently playing there.
     *
     * This is Accessibility-based UI automation of an app already installed on the
     * user's own device — the same mechanism as [tapByText] elsewhere, not a
     * Yandex Music API integration. It's best-effort: Yandex Music's on-screen
     * labels aren't a documented contract, so this can stop matching if a future
     * version renames its "like" control, and it requires the Accessibility
     * permission (Settings > Accessibility > VoiceAgent) to be granted.
     */
    suspend fun likeCurrentTrackInYandexMusic(): Boolean {
        if (!mediaSessionReader.hasActiveSession(YANDEX_MUSIC_PACKAGE)) return false
        openApp(YANDEX_MUSIC_PACKAGE)
        delay(YANDEX_MUSIC_FOREGROUND_DELAY_MS) // дать экрану открыться/перерисоваться перед поиском кнопки
        return LIKE_LABEL_CANDIDATES.any { tapByText(it) }
    }

    /**
     * "Скачай Х" — намеренно НЕ делает тихую фоновую установку (`pm install`
     * без root тоже потребовал бы явного согласия системы, но с root это уже
     * было бы возможно сделать невидимо для пользователя — а это ровно тот
     * приём, которым пользуются трояны-дропперы для скрытой установки кода).
     * Вместо этого: для имени приложения — открывает страницу в Google Play
     * (обычный, безопасный путь); для прямой ссылки на .apk — скачивает файл
     * и передаёт его системному установщику, который всё равно потребует
     * одно нажатие "Установить" от пользователя, как при скачивании из
     * браузера. Установка без этого подтверждения этим методом невозможна.
     */
    suspend fun installApp(nameOrUrl: String): InstallOutcome {
        val trimmed = nameOrUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            openPlayStoreListing(trimmed)
            return InstallOutcome.OpenedPlayStore
        }
        if (!trimmed.substringBefore("?").endsWith(".apk", ignoreCase = true)) {
            // Не прямая ссылка на apk (например ссылка на страницу в Google Play или
            // сторонний каталог) — безопаснее просто открыть её в браузере, чем гадать.
            openUrl(trimmed)
            return InstallOutcome.OpenedUrl
        }
        return try {
            val apkFile = downloadApk(trimmed)
            promptInstall(apkFile)
            InstallOutcome.PromptedInstall
        } catch (e: Exception) {
            InstallOutcome.Failed(e.message ?: "download error")
        }
    }

    private fun openPlayStoreListing(appName: String) {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (marketIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(marketIntent)
        } else {
            openUrl("https://play.google.com/store/search?q=$appName")
        }
    }

    private suspend fun downloadApk(url: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val file = File(dir, "download_${System.currentTimeMillis()}.apk")
        URL(url).openStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file
    }

    private fun promptInstall(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    sealed class InstallOutcome {
        object OpenedPlayStore : InstallOutcome()
        object OpenedUrl : InstallOutcome()
        object PromptedInstall : InstallOutcome()
        data class Failed(val reason: String) : InstallOutcome()
    }

    companion object {
        private const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"
        private const val YANDEX_MUSIC_FOREGROUND_DELAY_MS = 700L

        // Разные версии Яндекс Музыки и системный язык могут по-разному подписывать
        // кнопку "лайка" (или вовсе оставлять только contentDescription на иконке) —
        // пробуем несколько вариантов подряд, а не полагаемся на один точный текст.
        private val LIKE_LABEL_CANDIDATES = listOf(
            "Добавить в Мне нравится",
            "Мне нравится",
            "Убрать из Мне нравится",
            "Добавить в избранное",
            "Like",
            "Favorite"
        )
    }
}

