package com.voiceagent.oneplus13.integrations.messenger

import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException

/**
 * Unifies Telegram/MAX/WhatsApp under one interface: publishes every inbound
 * message onto AgentEventBus (so AgentOrchestrator or the lecture module can react
 * to "read me my messages"), and routes outbound replies to the right client.
 */
class MessengerBridge(
    private val telegram: TelegramClient,
    private val max: MaxClient,
    private val whatsApp: WhatsAppClient? = null
) {
    private val scope = CoroutineScope(Job())
    private var telegramOffset = 0L
    private var polling = false

    fun startPolling() {
        if (polling) return
        polling = true
        scope.launch { pollTelegramLoop() }
        // MAX's API contract in this version is a stub; do not poll it until implemented.
    }

    fun stopPolling() {
        polling = false
        scope.cancel()
    }

    private suspend fun pollTelegramLoop() {
        var delayMs = 1_000L
        while (polling) {
            try {
                val (newOffset, msgs) = telegram.pollUpdates(telegramOffset)
                telegramOffset = newOffset
                msgs.forEach { publishInbound(it) }
                delayMs = 1_000L
                delay(250)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("MessengerBridge", "Telegram polling failed; retrying")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun pollMaxLoop() {
        while (polling) {
            max.pollUpdates().forEach { publishInbound(it) }
            delay(3000)
        }
    }

    private suspend fun publishInbound(msg: Msg) {
        AgentEventBus.publish(
            AgentEvent.ActionRequested(
                action = "messenger_received",
                params = mapOf(
                    "platform" to msg.platform.name,
                    "from" to msg.senderName,
                    "chatId" to msg.chatId,
                    "text" to msg.text
                )
            )
        )
    }

    suspend fun send(platform: MessengerPlatform, chatId: String, text: String): Boolean = when (platform) {
        MessengerPlatform.TELEGRAM -> telegram.sendMessage(chatId, text)
        MessengerPlatform.MAX -> max.sendMessage(chatId, text)
        MessengerPlatform.WHATSAPP -> whatsApp?.sendMessage(chatId, text) ?: false
    }
}

