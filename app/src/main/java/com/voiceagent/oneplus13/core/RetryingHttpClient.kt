package com.voiceagent.oneplus13.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpResponse(val code: Int, val body: String)

/** Converts an OkHttp call into a cancellable operation and closes every response. */
internal suspend fun Call.awaitBody(): HttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { HttpResponse(it.code, it.body?.string().orEmpty()) }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

internal class RetryingHttpClient(
    private val http: OkHttpClient,
    private val maxRetries: Int,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun execute(request: Request, extract: (String) -> String?): String {
        var lastError: IOException? = null
        repeat(maxRetries.coerceAtLeast(0) + 1) { attempt ->
            try {
                val response = http.newCall(request).awaitBody()
                if (response.code in 200..299) {
                    return extract(response.body) ?: throw IllegalStateException("Empty LLM response")
                }
                if (response.code != 429 && response.code !in 500..599) {
                    throw IllegalStateException("LLM request failed: HTTP ${response.code}")
                }
                lastError = IOException("LLM request failed: HTTP ${response.code}")
            } catch (error: IOException) {
                lastError = error
            }
            if (attempt < maxRetries) pause(500L * (attempt + 1))
        }
        throw lastError ?: IOException("LLM request failed")
    }
}
