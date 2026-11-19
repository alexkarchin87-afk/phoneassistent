package com.voiceagent.oneplus13.local

import com.voiceagent.oneplus13.core.ChatMessage
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
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
    private external fun nativeGenerate(path: String, fd: Int, roles: Array<ByteArray>, messages: Array<ByteArray>, maxTokens: Int, thoughtful: Boolean): ByteArray
    private external fun nativeCancel()
    private external fun nativeReset()

    suspend fun complete(path: String, system: String, history: List<ChatMessage>, text: String, maxTokens: Int = 384, thoughtful: Boolean = false): String =
        completeInternal({ ModelHandle(path, null) }, system, history, text, maxTokens, thoughtful)

    suspend fun completeUri(context: Context, uri: Uri, system: String, history: List<ChatMessage>, text: String, thoughtful: Boolean = false): String =
        completeInternal({
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("LOCAL_FILE_UNAVAILABLE")
            try {
                val magic = ByteArray(4)
                val count = Os.read(fd.fileDescriptor, magic, 0, magic.size)
                Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
                check(count == 4 && magic.contentEquals(byteArrayOf(71, 71, 85, 70))) { "LOCAL_NOT_GGUF" }
                ModelHandle("", fd)
            } catch (e: Exception) { fd.close(); throw e }
        }, system, history, text, if (thoughtful) 1536 else 384, thoughtful)

    internal suspend fun completeDescriptorForTest(fd: ParcelFileDescriptor, system: String, text: String, thoughtful: Boolean = false, maxTokens: Int = 8): String =
        completeInternal({ ModelHandle("", ParcelFileDescriptor.dup(fd.fileDescriptor)) }, system, emptyList(), text, maxTokens, thoughtful)

    private data class ModelHandle(val path: String, val fd: ParcelFileDescriptor?) : AutoCloseable {
        override fun close() { fd?.close() }
    }

    private suspend fun completeInternal(open: () -> ModelHandle, system: String, history: List<ChatMessage>, text: String, maxTokens: Int, thoughtful: Boolean): String =
        suspendCancellableCoroutine { continuation ->
            if (text.length > 2500) {
                continuation.resumeWithException(IllegalArgumentException("LOCAL_PROMPT_TOO_LONG"))
                return@suspendCancellableCoroutine
            }
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
                    val effectiveSystem = if (thoughtful) "$system\n\nПроверяй вывод перед ответом, но сохраняй требуемый формат." else system
                    val chat = buildLocalMessages(effectiveSystem, history, text)
                    val result = open().use { handle ->
                        nativeGenerate(handle.path, handle.fd?.fd ?: -1, chat.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                            chat.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(), maxTokens, thoughtful)
                    }.toString(Charsets.UTF_8).trim()
                    val answer = stripReasoning(result)
                    if (answer.isEmpty()) error(if (thoughtful) "LOCAL_THINKING_INCOMPLETE" else "LOCAL_EMPTY_RESPONSE")
                    continuation.resume(answer)
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
        append("<|im_start|>user\n").append(safe(text)).append("<|im_end|>\n<|im_start|>assistant\n")
    }
}

/** Protect role delimiters when rendering untrusted user and history content. */
internal fun buildLocalMessages(system: String, history: List<ChatMessage>, text: String): List<ChatMessage> {
    fun safe(value: String) = listOf("<|im_start|>", "<|im_end|>", "<｜User｜>", "<｜Assistant｜>", "<｜end▁of▁sentence｜>")
        .fold(value) { acc, token -> acc.replace(token, "") }
    return listOf(ChatMessage("system", safe(system))) + history.takeLast(2)
        .filter { it.role == "user" || it.role == "assistant" }
        .map { ChatMessage(it.role, safe(it.content.take(900))) } + ChatMessage("user", safe(text))
}
internal fun stripReasoning(text: String): String {
    val begin = text.indexOf("<think>")
    if (begin < 0) return text.trim()
    val end = text.indexOf("</think>", begin + 7)
    if (end < 0) return ""
    return (text.substring(0, begin) + text.substring(end + 8)).trim()
}
