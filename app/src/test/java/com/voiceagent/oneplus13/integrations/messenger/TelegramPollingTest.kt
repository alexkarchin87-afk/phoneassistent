package com.voiceagent.oneplus13.integrations.messenger

import com.voiceagent.oneplus13.integrations.messenger.models.MessengerPlatform
import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class TelegramPollingTest {
    @Test fun failuresBackOffAndOffsetAdvancesAfterPublishing() = runBlocking {
        val offsets = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        val message = Msg(MessengerPlatform.TELEGRAM, "1", "Alice", "hello")
        var calls = 0
        val published = mutableListOf<String>()
        val poller = TelegramPolling(
            poll = { offset ->
                offsets.add(offset)
                when (++calls) {
                    1, 2 -> throw IOException("offline")
                    3 -> 12L to listOf(message)
                    else -> throw CancellationException("end test")
                }
            },
            publish = { published.add(it.text) },
            pause = { waits.add(it) }
        )
        try { poller.run() } catch (_: CancellationException) {}
        assertEquals(listOf(0L, 0L, 0L, 12L), offsets)
        assertEquals(listOf(1000L, 2000L, 250L), waits)
        assertEquals(listOf("hello"), published)
    }

    @Test fun publicationFailureDoesNotAdvanceOffset() = runBlocking {
        val offsets = mutableListOf<Long>()
        val message = Msg(MessengerPlatform.TELEGRAM, "1", "Alice", "hello")
        var calls = 0
        val poller = TelegramPolling(
            poll = { offset ->
                offsets.add(offset)
                if (++calls > 1) throw CancellationException("end test")
                99L to listOf(message)
            },
            publish = { throw IOException("consumer failed") },
            pause = {}
        )
        try { poller.run() } catch (_: CancellationException) {}
        assertEquals(listOf(0L, 0L), offsets)
    }

    @Test fun persistsEachSuccessfulMessageBeforeLaterFailureAndRestart() = runBlocking {
        var saved = 10L
        val observed = mutableListOf<Long>()
        val first = Msg(MessengerPlatform.TELEGRAM, "1", "A", "first", telegramUpdateId = 10)
        val second = Msg(MessengerPlatform.TELEGRAM, "1", "A", "second", telegramUpdateId = 11)
        var calls = 0
        val poller = TelegramPolling(
            poll = { offset ->
                observed.add(offset)
                if (++calls == 1) 13L to listOf(first, second)
                else throw CancellationException("restart")
            },
            publish = { if (it.text == "second") throw IOException("send failed") },
            pause = {}, loadOffset = { saved }, saveOffset = { saved = it }
        )
        try { poller.run() } catch (_: CancellationException) {}
        assertEquals(11L, saved)
        assertEquals(listOf(10L, 11L), observed)

        val restartOffsets = mutableListOf<Long>()
        val restarted = TelegramPolling(
            poll = { offset -> restartOffsets.add(offset); throw CancellationException("done") },
            publish = {}, loadOffset = { saved }, saveOffset = { saved = it }
        )
        try { restarted.run() } catch (_: CancellationException) {}
        assertEquals(listOf(11L), restartOffsets)
    }
}
