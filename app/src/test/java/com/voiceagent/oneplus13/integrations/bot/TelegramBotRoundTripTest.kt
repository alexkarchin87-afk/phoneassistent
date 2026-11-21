package com.voiceagent.oneplus13.integrations.bot

import com.voiceagent.oneplus13.integrations.messenger.TelegramClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Exercises Telegram JSON parsing -> authorization -> generated reply -> Telegram JSON. */
class TelegramBotRoundTripTest {
    @Test fun onlyTheApprovedPrivateChatReceivesAnAnswer() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = TelegramClient(botToken = "123:test", apiBase = server.url("/").toString().trimEnd('/'))
            val handler = TelegramBotHandler(
                ownerId = { "42" }, onCandidate = { _, _ -> fail("Unexpected candidate") },
                send = client::sendMessage, answer = { _, text -> "Ответ на: $text" },
                errorMessage = { "Ошибка" }
            )
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":[{"update_id":7,"message":{"text":"Привет","chat":{"id":42,"type":"private"},"from":{"id":42,"is_bot":false,"first_name":"Alice"}}}]}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{}}"""))
            val (offset, messages) = client.pollUpdates(0)
            handler.handle(messages.single())
            assertEquals(8L, offset)
            assertTrue(server.takeRequest().path!!.contains("getUpdates"))
            val outbound = server.takeRequest()
            assertTrue(outbound.path!!.contains("sendMessage"))
            assertEquals("Ответ на: Привет", org.json.JSONObject(outbound.body.readUtf8()).getString("text"))
        } finally { server.shutdown() }
    }
}
