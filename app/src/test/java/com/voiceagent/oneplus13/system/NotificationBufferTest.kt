package com.voiceagent.oneplus13.system

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBufferTest {
    @Test fun countsSameInstantEventsOnceAndKeepsOnlyTheNewestTwoHundred() {
        val start = NotificationBuffer.since(0).sequence
        repeat(200) { NotificationBuffer.record("chat.a") }
        NotificationBuffer.record("chat.b")
        val current = NotificationBuffer.since(start)
        assertEquals(mapOf("chat.a" to 199, "chat.b" to 1), current.counts)
        assertEquals(emptyMap<String, Int>(), NotificationBuffer.since(current.sequence).counts)
        NotificationBuffer.record("chat.b")
        assertEquals(mapOf("chat.b" to 1), NotificationBuffer.since(current.sequence).counts)
    }
}
