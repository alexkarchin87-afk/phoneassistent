package com.voiceagent.oneplus13.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRedactionTest {
    @Test fun redactsTokensButRetainsUsefulDetails() {
        val secret = "123456789:abcdefghijklmnopqrstuvwxyzABCDEFGHI"
        val input = "IOException https://api.telegram.org/bot$secret/getUpdates?offset=5\n" +
            "https://example.com/api?access_token=secret%2Fvalue&limit=3\nBearer abc.def-123\nAt Handler.kt:42"
        val result = redactSecrets(input)
        assertFalse(result.contains(secret))
        assertFalse(result.contains("secret%2Fvalue"))
        assertFalse(result.contains("abc.def-123"))
        assertTrue(result.contains("Handler.kt:42"))
        assertTrue(result.contains("offset=5"))
    }
}
