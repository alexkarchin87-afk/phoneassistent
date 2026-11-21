package com.voiceagent.oneplus13.system

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

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
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return null
        val candidates = pm.queryIntentActivities(launcherIntent, 0)

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

    suspend fun swipeUp(): Boolean {
        val service = AccessibilityAgent.instance ?: return false
        val metrics = context.resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
        return service.swipe(
            x1 = metrics.widthPixels * 0.5f, y1 = metrics.heightPixels * 0.8f,
            x2 = metrics.widthPixels * 0.5f, y2 = metrics.heightPixels * 0.3f
        )
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
        val service = AccessibilityAgent.instance ?: return false
        return LIKE_LABEL_CANDIDATES.any { service.tapByExactText(it) }
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
        if (trimmed.isEmpty()) return InstallOutcome.Failed("Название приложения или ссылка пусты.")
        return try {
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                openPlayStoreListing(trimmed)
                InstallOutcome.OpenedPlayStore
            } else if (!trimmed.substringBefore("?").endsWith(".apk", ignoreCase = true)) {
                openUrl(trimmed)
                InstallOutcome.OpenedUrl
            } else if (!trimmed.startsWith("https://")) {
                InstallOutcome.Failed("Для загрузки APK нужна ссылка HTTPS.")
            } else if (!context.packageManager.canRequestPackageInstalls()) {
                InstallOutcome.NeedsInstallPermission
            } else {
                val apkFile = downloadApk(trimmed)
                promptInstall(apkFile)
                InstallOutcome.PromptedInstall
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            InstallOutcome.Failed("Не удалось открыть установщик или скачать APK. Проверь интернет и системные ограничения.")
        }
    }

    private fun openPlayStoreListing(appName: String) {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search").buildUpon().appendQueryParameter("q", appName).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (marketIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(marketIntent)
        } else {
            openUrl(Uri.parse("https://play.google.com/store/search").buildUpon().appendQueryParameter("q", appName).toString())
        }
    }

    private suspend fun downloadApk(url: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null) ?: throw java.io.IOException("No storage"), "downloads")
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Cannot create download directory")
        val file = File(dir, "download_${System.currentTimeMillis()}.apk")
        // Check each DNS result at connection time and every redirect; do not let a
        // model-supplied URL address local services or an HTTPS-to-HTTP redirect.
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !isPublicDownloadAddress(it) })
                    throw UnknownHostException("Local download address is not allowed")
            }
        }
        val client = OkHttpClient.Builder().dns(dns).proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        try {
            // Only clean our own old downloads, long after the installer may have opened them.
            dir.listFiles()?.filter { it.name.matches(Regex("download_[0-9]+\\.apk")) &&
                it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
            var currentUrl = url.toHttpUrl()
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (currentUrl.scheme != "https") throw java.io.IOException("HTTPS required")
                val host = currentUrl.host
                if ((host.contains(':') || host.all { it.isDigit() || it == '.' }) &&
                    !isPublicDownloadAddress(InetAddress.getByName(host))) throw java.io.IOException("Local address")
                val response = client.newCall(Request.Builder().url(currentUrl).get().build()).execute()
                if (response.code in 300..399) {
                    val location = response.header("Location")
                    response.close()
                    if (++redirects > 5 || location == null) throw java.io.IOException("Invalid redirect")
                    currentUrl = currentUrl.resolve(location) ?: throw java.io.IOException("Invalid redirect")
                    continue
                }
                response.use {
                    if (!it.isSuccessful) throw java.io.IOException("Download failed")
                    val body = it.body ?: throw java.io.IOException("Empty download")
            val maxBytes = 250L * 1024 * 1024
                    if (body.contentLength() > maxBytes) throw java.io.IOException("Download too large")
            var total = 0L
                    body.byteStream().use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw java.io.IOException("Download too large")
                    output.write(buffer, 0, count)
                }
            } }
                    // APK is a ZIP archive. Check signature without trusting Content-Type.
                    val signature = file.inputStream().use { stream -> ByteArray(4).also { bytes ->
                        if (stream.read(bytes) != 4) throw java.io.IOException("Invalid APK")
                    } }
                    if (!signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
                        throw java.io.IOException("Invalid APK")
                }
                break
            }
            file
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally { client.connectionPool.evictAll() }
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
        object NeedsInstallPermission : InstallOutcome()
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
            "Добавить в избранное",
            "Add to favorites",
            "Add to favourites"
        )
    }
}

