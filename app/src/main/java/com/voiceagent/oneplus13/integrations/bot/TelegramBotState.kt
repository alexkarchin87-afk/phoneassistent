package com.voiceagent.oneplus13.integrations.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-local connection state. No token or conversation content is exposed. */
data class TelegramState(
    val running: Boolean = false,
    val status: String = "Остановлен",
    val candidateId: String? = null,
    val candidateName: String? = null,
    val approvedId: String? = null
)

object TelegramBotState {
    private val mutable = MutableStateFlow(TelegramState())
    val state = mutable.asStateFlow()
    fun update(block: (TelegramState) -> TelegramState) { mutable.update(block) }
}
