package com.voiceagent.oneplus13.integrations.messenger

import com.voiceagent.oneplus13.integrations.messenger.models.Msg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Polling is bounded, cancellable and advances the offset only after publication. */
internal class TelegramPolling(
    private val poll: suspend (Long) -> Pair<Long, List<Msg>>,
    private val publish: suspend (Msg) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val reportError: (Exception) -> Unit = {}
) {
    suspend fun run() {
        var offset = 0L
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                val (nextOffset, messages) = poll(offset)
                messages.forEach { publish(it) }
                offset = nextOffset
                backoff = 1_000L
                pause(250L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
                pause(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }
}
