package com.voiceagent.oneplus13.integrations.messenger

import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.security.SecretsStore

/**
 * Watches Telegram notifications (forwarded by NotificationReader) for a message
 * from @BotFather and pulls out a bot API token if one is present.
 *
 * Deliberately does NOT auto-apply the token anywhere: it stores it under a
 * "pending" key and publishes an event so AgentOrchestrator can ask the user out
 * loud to confirm before wiring it into a generated project. A token is a secret
 * that grants control of a Telegram bot — grabbing it from a notification is a
 * convenience for the user's OWN device and OWN bot, but using it should always be
 * an explicit, spoken "да".
 */
class TokenCapture(private val secrets: SecretsStore) {

    private val tokenRegex = Regex("""(?<![\w-])(\d{6,15}:[A-Za-z0-9_-]{35})(?![\w-])""")
    private val telegramPackages = setOf("org.telegram.messenger", "org.telegram.messenger.web")

    /** Call from NotificationReader for every posted notification. Returns true if a
     *  token was found and a confirmation event was published. */
    suspend fun onNotification(packageName: String, title: String, text: String, forProjectName: String?): Boolean {
        if (packageName !in telegramPackages) return false
        if (forProjectName.isNullOrBlank()) return false
        if (!title.trim().equals("BotFather", ignoreCase = true)) return false

        val match = tokenRegex.find(text) ?: return false
        val token = match.value
        val project = forProjectName
        secrets.put(SecretsStore.pendingBotFatherTokenKey(project), token)

        AgentEventBus.publish(
            AgentEvent.ActionRequested(
                action = "botfather_token_found",
                params = mapOf(
                    "project" to project,
                    "masked_token" to maskToken(token)
                )
            )
        )
        return true
    }

    fun consumePendingToken(projectName: String): String? {
        val key = SecretsStore.pendingBotFatherTokenKey(projectName)
        val token = secrets.get(key)
        if (token != null) secrets.remove(key)
        return token
    }

    private fun maskToken(token: String): String {
        val parts = token.split(":")
        val id = parts.getOrNull(0) ?: return "***"
        return "$id:••••••••"
    }
}
