package com.voiceagent.oneplus13.system

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A fixed allowlist of device operations. Model-generated shell commands are never executed. */
object RootActions {
    enum class Toggle(val serviceName: String) { WIFI("wifi"), BLUETOOTH("bluetooth"), MOBILE_DATA("data") }
    sealed class Result {
        object Success : Result()
        object NoRoot : Result()
        data class Error(val message: String) : Result()
    }

    internal fun isRootIdentity(output: String): Boolean =
        Regex("(?:^|\\s)uid=0(?:\\(|\\s|$)").containsMatchIn(output)

    /** Explicit, user-initiated probe. Magisk may prompt the user to grant or deny access. */
    fun checkAccess(): Result = runAsRoot("id", requireRootId = true)

    fun setToggle(toggle: Toggle, enabled: Boolean): Result =
        runAsRoot("svc ${toggle.serviceName} ${if (enabled) "enable" else "disable"}")

    private fun runAsRoot(command: String, requireRootId: Boolean = false): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = thread(start = true, isDaemon = true, name = "voiceagent-su-output") {
                runCatching {
                    process.inputStream.bufferedReader().use { input ->
                        val buf = CharArray(1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (output.length < 8192) output.append(buf, 0, minOf(n, 8192 - output.length))
                        }
                    }
                }
            }
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    Result.Error("Root-запрос не получил ответа за 30 секунд. Проверь окно Magisk и повтори.")
                } else {
                    reader.join(1000)
                    if (process.exitValue() != 0) Result.Error("Команда не выполнена. Проверь разрешение root в Magisk и поддержку команды телефоном.")
                    else if (requireRootId && (reader.isAlive || !isRootIdentity(output.toString())))
                        Result.Error("Команда не получила права root. Проверь разрешение в Magisk.")
                    else Result.Success
                }
            } finally {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (_: IOException) {
            Result.NoRoot
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Error("Проверка root прервана.")
        } catch (_: SecurityException) {
            Result.Error("Система запретила запуск su.")
        }
    }
}
