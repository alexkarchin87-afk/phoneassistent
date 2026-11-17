package com.voiceagent.oneplus13.integrations.messenger

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TelegramClientTest {
    @Test fun parsesPrivateAndGroupChatsAndPostsValidJson() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = TelegramClient(botToken = "123:test", apiBase = server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":[{"update_id":10,"message":{"text":"/start","chat":{"id":42,"type":"private"},"from":{"id":42,"is_bot":false,"first_name":"Alice"}}},{"update_id":11,"message":{"text":"hello","chat":{"id":-1,"type":"group"},"from":{"id":42,"is_bot":false}}}]}"""))
            val (offset, messages) = client.pollUpdates(0)
            assertEquals(12L, offset)
            assertTrue(messages[0].isPrivateUserChat)
            assertFalse(messages[1].isPrivateUserChat)
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{}}"""))
            assertTrue(client.sendMessage("42", "test\n\ttext"))
            server.takeRequest()
            val request = server.takeRequest()
            val payload = org.json.JSONObject(request.body.readUtf8())
            assertEquals("test\n\ttext", payload.getString("text"))
            assertEquals("42", payload.getString("chat_id"))
        } finally { server.shutdown() }
    }
    @Test fun httpConflictIsVisible() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = TelegramClient(botToken = "123:test", apiBase = server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setResponseCode(409))
            try { client.pollUpdates(0); fail("Expected error") }
            catch (e: java.io.IOException) { assertTrue(e.message!!.contains("409")) }
        } finally { server.shutdown() }
    }
}
