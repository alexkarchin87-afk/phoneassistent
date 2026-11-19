package com.voiceagent.oneplus13.local

import com.voiceagent.oneplus13.core.ChatMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One CPU inference at a time, including callers from the bot and voice service. */
object LocalInference {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var active: Any? = null
    private val libraryLoaded = runCatching { System.loadLibrary("voiceagent_local") }
    private external fun nativeGenerate(path: String, prompt: ByteArray): ByteArray
    private external fun nativeCancel()
    private external fun nativeReset()

    suspend fun complete(path: String, system: String, history: List<ChatMessage>, text: String): String =
        suspendCancellableCoroutine { continuation ->
            val request = Any()
            continuation.invokeOnCancellation {
                synchronized(lock) { if (active === request && libraryLoaded.isSuccess) nativeCancel() }
            }
            executor.execute {
                val run = synchronized(lock) {
                    if (!continuation.isActive) false else {
                        active = request
                        if (libraryLoaded.isSuccess) nativeReset()
                        true
                    }
                }
                if (!run) return@execute
                try {
                    libraryLoaded.getOrThrow()
                    val prompt = qwenPrompt(system, history, text)
                    val result = nativeGenerate(path, prompt.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8).trim()
                    if (result.isEmpty()) error("LOCAL_EMPTY_RESPONSE")
                    continuation.resume(result)
                } catch (e: Throwable) {
                    continuation.resumeWithException(e)
                } finally {
                    synchronized(lock) { if (active === request) active = null }
                }
            }
        }
}

/** ChatML for the supported Qwen model. Keep the full action policy, shorten history. */
internal fun qwenPrompt(system: String, history: List<ChatMessage>, text: String): String {
    fun safe(value: String) = value.replace("<|im_start|>", "").replace("<|im_end|>", "")
    return buildString {
        append("<|im_start|>system\n").append(safe(system)).append("<|im_end|>\n")
        history.takeLast(2).forEach { message ->
            if (message.role == "user" || message.role == "assistant") {
                append("<|im_start|>").append(message.role).append('\n')
                append(safe(message.content.take(900))).append("<|im_end|>\n")
            }
        }
        append("<|im_start|>user\n").append(safe(text.take(2500))).append("<|im_end|>\n<|im_start|>assistant\n")
    }
}
