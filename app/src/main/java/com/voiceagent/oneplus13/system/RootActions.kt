package com.voiceagent.oneplus13.system

import java.io.IOException
import java.util.concurrent.TimeUnit

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
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Result.Error("Root-запрос не получил ответа за 30 секунд. Проверь окно Magisk и повтори.")
            } else if (process.exitValue() != 0) {
                Result.Error("Команда не выполнена. Проверь разрешение root в Magisk и поддержку команды телефоном.")
            } else if (requireRootId && !process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
                    .let(::isRootIdentity)) {
                Result.Error("Команда не получила права root. Проверь разрешение в Magisk.")
            } else Result.Success
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
