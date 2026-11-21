package com.voiceagent.oneplus13.system

import android.content.Context

/** A local-only digest. Notification content must never be sent to an LLM here. */
class ChatsDigest(private val context: Context) {
    private var lastSeen = 0L

    suspend fun summarizeSinceLastDigest(): String {
        val snapshot = NotificationBuffer.since(lastSeen)
        if (snapshot.counts.isEmpty()) return "Новых доступных уведомлений нет."
        val digest = snapshot.counts.entries.joinToString(". ") { (pkg, count) ->
            val name = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            "$name: уведомлений $count"
        }
        lastSeen = snapshot.sequence
        return digest
    }
}
