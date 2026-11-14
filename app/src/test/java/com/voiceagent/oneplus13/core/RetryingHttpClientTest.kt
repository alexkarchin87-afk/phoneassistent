package com.voiceagent.oneplus13.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetryingHttpClientTest {
    @Test fun retriesTemporaryHttpFailure() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("response"))
            server.start()
            val waits = mutableListOf<Long>()
            val client = RetryingHttpClient(OkHttpClient(), 2) { waits.add(it) }
            val result = client.execute(Request.Builder().url(server.url("/")).build()) { it }
            assertEquals("response", result)
            assertEquals(listOf(500L), waits)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun cancellationCancelsTheUnderlyingCall() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val job = launch { call.awaitBody() }
            // Yield so the asynchronous call is started before waiting for the request.
            kotlinx.coroutines.yield()
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            server.shutdown()
        }
    }

    @Test fun clientErrorsAreNotRetried() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val client = RetryingHttpClient(OkHttpClient(), 2) { error("unexpected retry") }
            try {
                client.execute(Request.Builder().url(server.url("/")).build()) { it }
                error("expected failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("401"))
                assertEquals(1, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }
}
