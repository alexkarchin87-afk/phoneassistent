package com.voiceagent.oneplus13.system

import android.content.Context
import com.voiceagent.oneplus13.core.LLMClient

/** A local-only digest. Notification content must never be sent to an LLM here. */
class ChatsDigest(private val context: Context, private val llm: LLMClient) {
    private var lastDigestAtMillis = 0L

    suspend fun summarizeSinceLastDigest(): String {
        val now = System.currentTimeMillis()
        val entries = NotificationBuffer.entriesSince(lastDigestAtMillis)
            .filter { it.packageName in ALLOWED_PACKAGES }
            .filterNot { SENSITIVE.containsMatchIn(it.title + " " + it.text) }
            .takeLast(4)
        lastDigestAtMillis = now
        if (entries.isEmpty()) return "Новых доступных уведомлений нет."
        // Announce only the source and count. Message bodies may contain private data.
        val counts = entries.groupingBy { it.packageName }.eachCount()
        return counts.entries.joinToString(". ") { (pkg, count) ->
            val name = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            "$name: уведомлений $count"
        }
    }

    companion object {
        private val ALLOWED_PACKAGES = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web",
            "com.whatsapp", "com.whatsapp.w4b", "com.discord"
        )
        private val SENSITIVE = Regex(
            "(?i)(код|парол|банк|карт[аы]|плат[её]ж|otp|password|verification|\\b[0-9]{4,8}\\b)"
        )
    }
}
