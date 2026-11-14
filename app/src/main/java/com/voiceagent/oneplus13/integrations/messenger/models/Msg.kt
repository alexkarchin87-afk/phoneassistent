package com.voiceagent.oneplus13.integrations.messenger.models

import com.squareup.moshi.JsonClass

enum class MessengerPlatform { TELEGRAM, MAX, WHATSAPP }

/** Platform-agnostic message shape used by MessengerBridge, so AgentOrchestrator
 *  never has to know which messenger a message came from. */
@JsonClass(generateAdapter = false)
data class Msg(
    val platform: MessengerPlatform,
    val chatId: String,
    val senderName: String,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis()
)
