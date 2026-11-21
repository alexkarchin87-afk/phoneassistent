package com.voiceagent.oneplus13.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonExtractionTest {
    @Test fun extractsOneObjectAndIgnoresBracesInsideQuotedStrings() {
        val json = """{"text":"a } b { c","nested":{"x":1}}"""
        assertEquals(json, extractJsonObject("Ввод: $json пояснение"))
        assertEquals(json, extractJsonObject("$json trailing"))
    }
    @Test fun handlesEscapedQuotesAndIncompleteJson() {
        val json = """{"text":"quoted \"} still a string","x":1}"""
        assertEquals(json, extractJsonObject("```json\n$json\n```"))
        assertNull(extractJsonObject("""{"text":"not closed }"""))
    }
}
