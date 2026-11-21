package com.voiceagent.oneplus13.integrations.bot

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voiceagent.oneplus13.R
import com.voiceagent.oneplus13.VoiceAgentApp
import com.voiceagent.oneplus13.agent.friendlyAssistantError
import com.voiceagent.oneplus13.core.LLMClient
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.integrations.messenger.MaxClient
import com.voiceagent.oneplus13.integrations.messenger.MessengerBridge
import com.voiceagent.oneplus13.integrations.messenger.TelegramClient

class TelegramBotService : Service() {
    private var bridge: MessengerBridge? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        TelegramBotState.update { it.copy(running = true, status = "Подключаемся к Telegram…", candidateId = null, candidateName = null) }
        try {
            val secrets = SecretsStore(applicationContext)
            if (secrets.get(SecretsStore.KEY_TELEGRAM_BOT_TOKEN).isNullOrBlank()) {
                TelegramBotState.update { it.copy(status = "Сначала сохрани токен Telegram.") }
                stopSelf()
                return
            }
            val telegram = TelegramClient(applicationContext)
            val llm = LLMClient(applicationContext)
            val handler = TelegramBotHandler(
                ownerId = { secrets.get(SecretsStore.KEY_TELEGRAM_OWNER_ID)?.takeIf(String::isNotBlank) },
                onCandidate = { id, name ->
                    TelegramBotState.update { it.copy(candidateId = id, candidateName = name, status = "Запрос на подключение: подтверди свой чат ниже.") }
                },
                send = { chat, text -> telegram.sendMessage(chat, text) },
                answer = { history, text -> llm.complete(
                    "Ты — Ваня, дружелюбный текстовый ассистент. Отвечай по-русски. Ты не можешь управлять телефоном через Telegram и не должен утверждать, что выполнил действия на устройстве.",
                    history, text
                ) },
                errorMessage = ::friendlyAssistantError
            )
            TelegramBotState.update { it.copy(approvedId = secrets.get(SecretsStore.KEY_TELEGRAM_OWNER_ID)) }
            bridge = MessengerBridge(
                telegram, MaxClient(applicationContext),
                onMessage = handler::handle,
                onConnected = { name -> TelegramBotState.update { it.copy(status = if (name.isBlank()) "Telegram подключён" else "Подключён @$name. Напиши ему /start.") } },
                onError = { error -> TelegramBotState.update { it.copy(status = telegramConnectionError(error)) } }
            ).also { it.startPolling() }
        } catch (error: Exception) {
            TelegramBotState.update { it.copy(running = false, status = "Не удалось запустить Telegram. Проверь настройки и повтори.") }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) {
        bridge?.stopPolling()
        TelegramBotState.update { it.copy(status = "Android остановил Telegram по ограничению времени. Запусти его снова.") }
        stopSelf(startId)
    }
    override fun onDestroy() {
        bridge?.stopPolling()
        TelegramBotState.update { it.copy(running = false, candidateId = null, candidateName = null) }
        super.onDestroy()
    }
    private fun buildNotification() = NotificationCompat.Builder(this, VoiceAgentApp.CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("Telegram-бот запущен")
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setOngoing(true).build()
    companion object { private const val NOTIF_ID = 1002 }
}

internal fun telegramConnectionError(error: Throwable): String = when {
    error.message.orEmpty().contains("HTTP 401") -> "Telegram отклонил токен. Проверь его в настройках."
    error.message.orEmpty().contains("HTTP 409") -> "Telegram занят другим запуском или webhook. Останови второй бот и проверь webhook."
    error.message.orEmpty().contains("HTTP 429") -> "Telegram ограничил запросы. Повторяем позже."
    else -> "Нет связи с Telegram или не удалось отправить ответ. Повторяем подключение."
}
