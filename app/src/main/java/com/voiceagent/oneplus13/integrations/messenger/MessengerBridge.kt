package com.voiceagent.oneplus13.integrations.messenger

import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs Telegram polling and forwards messages to the explicitly configured handler. */
class MessengerBridge(
    private val telegram: TelegramClient,
    private val max: MaxClient,
    private val whatsApp: WhatsAppClient? = null,
    private val onMessage: suspend (Msg) -> Unit = {},
    private val onConnected: (String) -> Unit = {},
    private val onError: (Exception) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling = false

    fun startPolling() {
        if (polling) return
        polling = true
        scope.launch {
            var verified = false
            TelegramPolling(
                poll = {
                    if (!verified) {
                        val username = telegram.botUsername()
                        verified = true
                        onConnected(username)
                    }
                    telegram.pollUpdates(it)
                },
                publish = onMessage,
                reportError = { error -> verified = false; onError(error) }
            ).run()
        }
    }

    fun stopPolling() { polling = false; scope.cancel() }

    suspend fun send(platform: MessengerPlatform, chatId: String, text: String): Boolean = when (platform) {
        MessengerPlatform.TELEGRAM -> telegram.sendMessage(chatId, text)
        MessengerPlatform.MAX -> max.sendMessage(chatId, text)
        MessengerPlatform.WHATSAPP -> whatsApp?.sendMessage(chatId, text) ?: false
    }
}
