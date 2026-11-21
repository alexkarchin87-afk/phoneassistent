package com.voiceagent.oneplus13.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ловит любой необработанный краш всего приложения и сохраняет полный
 * стектрейс в файл — чтобы вместо голого .apk можно было прислать текст
 * реальной ошибки: тогда баг чинится по конкретной строке кода, а не
 * вслепую.
 *
 * После записи ОБЯЗАТЕЛЬНО передаёт краш дальше системному обработчику
 * ([previousHandler]) — иначе вместо стандартного диалога "Приложение
 * остановлено" процесс может просто зависнуть.
 */
object CrashLogger {

    private const val MAX_KEPT_CRASHES = 5

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashFile(appContext, thread, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Файл последнего краша — для кнопки "Поделиться" в Настройках. */
    fun latestCrashFile(context: Context): File? =
        crashDir(context).listFiles()
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }

    fun hasAnyCrash(context: Context): Boolean = latestCrashFile(context) != null

    /** Old logs may predate redaction. Always prepare a scrubbed copy before sharing. */
    fun crashFileForSharing(context: Context): File? {
        val source = latestCrashFile(context) ?: return null
        return runCatching {
            File(context.cacheDir, "voiceagent_crash_share.txt").apply {
                writeText(redactSecrets(source.readText()))
            }
        }.getOrNull()
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context).apply { mkdirs() }

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.txt")

        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        file.writeText(
            buildString {
                appendLine("VoiceAgent (\"Ваня\") — краш $stamp")
                appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Поток: ${thread.name}")
                appendLine()
                append(redactSecrets(stackTrace))
            }
        )

        // Не даём папке расти бесконечно — оставляем только последние N крашей.
        dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEPT_CRASHES)
            ?.forEach { it.delete() }
    }

    private fun crashDir(context: Context): File = File(context.filesDir, "crashes")
}

/** Redacts known credential shapes while retaining paths and exception details. */
internal fun redactSecrets(text: String): String = text
    .replace(Regex("(?i)(bot)([0-9]{6,15}):([a-z0-9_-]{20,})"), "$1[REDACTED]")
    .replace(Regex("(?i)([?&](?:access_token|api_key|token)=)[^&#\\s]+"), "$1[REDACTED]")
    .replace(Regex("(?i)(bearer\\s+)[a-z0-9._~+/-]+=*"), "$1[REDACTED]")
