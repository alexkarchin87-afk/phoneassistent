package com.voiceagent.oneplus13.agent

import com.voiceagent.oneplus13.core.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEventBridgeTest {
    @Test fun fullEventBurstCannotBlockPublisherHoldingConversationMutex() = runBlocking {
        val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 32)
        val mutex = Mutex()
        val delivered = CompletableDeferred<AgentEvent>()
        val workers = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            workers.bridgeTokenEvents(events, mutex) { delivered.complete(it) }
            withTimeout(3000) { events.subscriptionCount.first { it > 0 } }
            mutex.withLock {
                events.emit(AgentEvent.ActionRequested("botfather_token_found", emptyMap()))
                withTimeout(3000) { repeat(100) { events.emit(AgentEvent.Reply("x")) } }
            }
            val received = withTimeout(3000) { delivered.await() } as AgentEvent.ActionRequested
            assertEquals("botfather_token_found", received.action)
        } finally { workers.cancel() }
    }
}
