package com.voiceagent.oneplus13.integrations.bot

import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TelegramBotHandlerTest {
    private fun msg(chat: String, text: String, private: Boolean = true) =
        Msg(MessengerPlatform.TELEGRAM, chat, "Alice", text, isPrivateUserChat = private)

    @Test fun pairingAndOwnerOnlyAnswers() = runBlocking {
        var owner: String? = null
        val candidates = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String>>()
        var modelCalls = 0
        val handler = TelegramBotHandler(
            ownerId = { owner }, onCandidate = { id, _ -> candidates.add(id) },
            send = { id, text -> sent.add(id to text); true },
            answer = { _, _ -> modelCalls++; "Ответ" }, errorMessage = { "Ошибка" }
        )
        handler.handle(msg("99", "/start", false))
        handler.handle(msg("42", "обычный текст"))
        assertTrue(candidates.isEmpty())
        assertEquals(0, modelCalls)
        handler.handle(msg("42", "/start"))
        assertEquals(listOf("42"), candidates)
        owner = "42"
        handler.handle(msg("99", "привет"))
        assertEquals(0, modelCalls)
        handler.handle(msg("42", "привет"))
        assertEquals(1, modelCalls)
        assertEquals("42" to "Ответ", sent.last())
    }

    @Test fun modelFailureIsReportedAndLongResponsesAreSplit() = runBlocking {
        val sent = mutableListOf<String>()
        var fail = true
        val handler = TelegramBotHandler(
            ownerId = { "42" }, onCandidate = { _, _ -> }, send = { _, text -> sent.add(text); true },
            answer = { _, _ -> if (fail) throw IllegalStateException() else "x".repeat(7001) },
            errorMessage = { "Проверь ключ" }
        )
        handler.handle(msg("42", "привет"))
        assertEquals("Проверь ключ", sent.single())
        fail = false
        sent.clear()
        handler.handle(msg("42", "снова"))
        assertEquals(listOf(3500, 3500, 1), sent.map { it.length })
    }

    @Test fun revokedOwnerDoesNotReceiveDelayedModelResponse() = runBlocking {
        var owner: String? = "42"
        val sent = mutableListOf<String>()
        val handler = TelegramBotHandler(
            ownerId = { owner }, onCandidate = { _, _ -> }, send = { _, text -> sent.add(text); true },
            answer = { _, _ -> owner = null; "secret answer" }, errorMessage = { "error" }
        )
        handler.handle(msg("42", "привет"))
        assertTrue(sent.isEmpty())
    }
}
