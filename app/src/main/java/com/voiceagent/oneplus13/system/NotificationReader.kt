package com.voiceagent.oneplus13.system

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.integrations.messenger.TokenCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Surfaces incoming notifications (e.g. new Telegram/WhatsApp messages) to the
 * agent so it can be asked "what did I just get from Vika?" or read messages aloud.
 * Also feeds Telegram notifications into TokenCapture, so a fresh @BotFather token
 * can be picked up automatically for the last project AgentOrchestrator generated.
 *
 * "Ответь [Имя] [Текст]" without opening the messenger works by re-using the
 * SAME quick-reply action the messenger itself already put on its notification
 * (the same mechanism Wear OS / Android Auto use) — VoiceAgent never talks to
 * Telegram/WhatsApp servers directly and never needs their API keys for this.
 *
 * Requires the user to grant Notification Access once in system settings.
 */
class NotificationReader : NotificationListenerService() {

    private val scope = CoroutineScope(Job())
    private lateinit var tokenCapture: TokenCapture

    /** Последнее уведомление с доступным быстрым ответом на отправителя (ключ —
     *  заголовок уведомления в нижнем регистре, обычно это имя собеседника). */
    private val replyableBySender = java.util.concurrent.ConcurrentHashMap<String, StatusBarNotification>()

    override fun onCreate() {
        super.onCreate()
        tokenCapture = TokenCapture(SecretsStore(applicationContext))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        if (sbn.packageName in REPLY_PACKAGES && !SENSITIVE.containsMatchIn("$title $text")
            && findReplyAction(sbn) != null) {
            replyableBySender[sbn.key] = sbn
        }

        scope.launch {
            val handledAsToken = tokenCapture.onNotification(
                packageName = sbn.packageName,
                title = title,
                text = text,
                forProjectName = PendingProject.awaitingTokenFor
            )
            if (handledAsToken) return@launch

            // Never retain or broadcast notifications from arbitrary apps, including
            // banks and SMS; never retain obvious codes or authentication messages.
            if (sbn.packageName !in REPLY_PACKAGES ||
                SENSITIVE.containsMatchIn("$title $text")) return@launch

            NotificationBuffer.record(NotificationEntry(sbn.packageName, title, text))

            AgentEventBus.publish(
                AgentEvent.ActionRequested(
                    action = "notification_received",
                    params = mapOf(
                        "package" to sbn.packageName,
                        "title" to title,
                        "text" to text
                    )
                )
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        replyableBySender.remove(sbn.key)
    }

    /** "Ответь Вике привет" — находит уведомление от отправителя, чьё имя
     *  содержит [senderNameQuery], и отправляет [text] через собственное
     *  действие "быстрый ответ" этого уведомления (RemoteInput), в точности
     *  как это делает системная плашка "Ответить" в шторке. Ничего не
     *  открывает на экране. Возвращает false, если такого уведомления с
     *  быстрым ответом сейчас нет (например, уже прочитано/смахнуто).
     */
    fun replyTo(senderNameQuery: String, text: String): Boolean {
        val q = senderNameQuery.trim().lowercase()
        if (q.isBlank()) return false
        // A single exact match from a known messenger is required; ambiguity fails closed.
        val entry = replyableBySender.entries.filter {
            it.value.notification.extras.getCharSequence("android.title")?.toString()?.trim()?.lowercase() == q
        }.singleOrNull() ?: return false
        val sbn = entry.value
        val action = findReplyAction(sbn) ?: return false
        val remoteInputs = action.remoteInputs ?: return false

        val intent = Intent()
        val bundle = android.os.Bundle()
        remoteInputs.forEach { ri -> bundle.putCharSequence(ri.resultKey, text) }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

        return try {
            action.actionIntent.send(applicationContext, 0, intent)
            replyableBySender.remove(entry.key)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    private fun findReplyAction(sbn: StatusBarNotification) =
        sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

    companion object {
        private val REPLY_PACKAGES = setOf("org.telegram.messenger", "org.telegram.messenger.web",
            "com.whatsapp", "com.whatsapp.w4b", "com.discord")
        private val SENSITIVE = Regex("(?i)(код|парол|банк|карт[аы]|плат[её]ж|otp|password|verification|\\b[0-9]{4,8}\\b)")
        /** Живой экземпляр запущенного слушателя — так же, как AccessibilityAgent.instance,
         *  чтобы AgentOrchestrator мог дёрнуть replyTo() без биндинга сервиса. */
        var instance: NotificationReader? = null
    }
}

/** Tiny process-wide handoff: AgentOrchestrator sets this right after it generates a
 *  project that needs a bot token, so NotificationReader knows which project a
 *  freshly-seen BotFather token belongs to. Cleared once the token is consumed. */
object PendingProject {
    var awaitingTokenFor: String? = null
}

