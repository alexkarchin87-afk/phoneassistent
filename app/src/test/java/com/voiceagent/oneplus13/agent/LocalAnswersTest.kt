package com.voiceagent.oneplus13.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class LocalAnswersTest {
    @Test fun matchesOnlyExplicitOfflineRequests() {
        assertTrue(localAnswer("Который час?", Date(0))!!.startsWith("Сейчас "))
        assertTrue(localAnswer("Какой сегодня день?", Date(0))!!.startsWith("Сегодня "))
        assertNull(localAnswer("Расскажи, сколько времени займёт поездка"))
    }
    @Test fun explainsApiFailures() {
        assertTrue(friendlyAssistantError(IllegalArgumentException("DeepSeek API key is not set")).contains("ключ"))
        assertTrue(friendlyAssistantError(IllegalStateException("LLM request failed: HTTP 402")).contains("баланс"))
        assertTrue(friendlyAssistantError(java.io.IOException("network")).contains("интернет"))
    }
}
