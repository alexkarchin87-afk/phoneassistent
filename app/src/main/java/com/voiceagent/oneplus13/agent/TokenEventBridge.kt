package com.voiceagent.oneplus13.agent

import com.voiceagent.oneplus13.core.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep the shared-flow collector independent from the conversation mutex. */
internal fun CoroutineScope.bridgeTokenEvents(events: Flow<AgentEvent>, mutex: Mutex, handle: suspend (AgentEvent) -> Unit) {
    val mailbox = Channel<AgentEvent>(Channel.CONFLATED)
    launch { for (event in mailbox) mutex.withLock { handle(event) } }
    launch {
        events.collect { event ->
            if (event is AgentEvent.ActionRequested && event.action == "botfather_token_found") mailbox.trySend(event)
        }
    }
}
