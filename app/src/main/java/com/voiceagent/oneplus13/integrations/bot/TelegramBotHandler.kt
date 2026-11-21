package com.voiceagent.oneplus13.integrations.bot

import com.voiceagent.oneplus13.core.ChatMessage
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.CancellationException

/** Restricted private-chat assistant. This path never invokes phone actions. */
internal class TelegramBotHandler(
    private val ownerId: () -> String?,
    private val onCandidate: (String, String) -> Unit,
    private val send: suspend (String, String) -> Boolean,
    private val answer: suspend (List<ChatMessage>, String) -> String,
    private val errorMessage: (Throwable) -> String
) {
    private val history = ArrayDeque<ChatMessage>()
    private var historyOwner: String? = null

    suspend fun handle(message: Msg) {
        if (!message.isPrivateUserChat || message.chatId.isBlank()) return
        val owner = ownerId()
        val command = message.text.trim().substringBefore(' ').lowercase()
        if (owner == null) {
            if (command == "/start") {
                onCandidate(message.chatId, message.senderName)
                requireSend(message.chatId, "Запрос получен. Подтверди этот чат в настройках Вани на телефоне, затем напиши /start ещё раз.")
            }
            return
        }
        if (message.chatId != owner) return
        if (historyOwner != owner) { history.clear(); historyOwner = owner }
        if (command == "/start" || command == "/help") {
            requireSend(owner, "Привет! Напиши мне сообщение. Для ответов нужен ключ DeepSeek в настройках Вани. Управление телефоном через Telegram пока недоступно.")
            return
        }
        if (message.text.length > 4000) {
            requireSend(owner, "Сообщение слишком длинное. Отправь до 4000 символов.")
            return
        }
        var generated = false
        val reply = try {
            answer(history.toList(), message.text).also { generated = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage(error)
        }
        if (ownerId() != owner) { history.clear(); historyOwner = null; return }
        // Telegram limits each message to 4096 characters; keep a little margin.
        reply.ifBlank { "Не получил ответа от модели." }.chunked(3500).forEach { requireSend(owner, it) }
        if (generated) {
            history.addLast(ChatMessage("user", message.text))
            history.addLast(ChatMessage("assistant", reply))
            while (history.size > 12) history.removeFirst()
        }
    }

    private suspend fun requireSend(chatId: String, text: String) {
        if (!send(chatId, text)) throw java.io.IOException("Telegram send failed")
    }
}
