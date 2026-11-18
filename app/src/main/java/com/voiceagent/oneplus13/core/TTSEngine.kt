package com.voiceagent.oneplus13.core

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Serializes speech and exposes a gate for suppressing recognition of our own voice. */
class TTSEngine(
    context: Context,
    private val onSpeechStateChanged: (Boolean) -> Unit = {}
) {
    private val initialized = CompletableDeferred<Boolean>()
    @Volatile private var closed = false
    @Volatile private var speaking = false
    @Volatile private var suppressUntil = 0L
    private val speechMutex = Mutex()
    private lateinit var tts: TextToSpeech
    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            // Post so the field assignment completes before the callback reads it.
            Handler(Looper.getMainLooper()).post {
                if (closed || status != TextToSpeech.SUCCESS) {
                    initialized.complete(false)
                } else {
                    try {
                        tts.language = Locale("ru", "RU")
                        initialized.complete(true)
                    } catch (error: RuntimeException) {
                        android.util.Log.e("TTSEngine", "Text-to-speech initialization failed", error)
                        initialized.complete(false)
                    }
                }
            }
        }
    }

    fun acceptsMicrophoneInput(): Boolean =
        !speaking && SystemClock.elapsedRealtime() >= suppressUntil

    suspend fun speak(text: String) = speechMutex.withLock {
        if (text.isBlank() || closed || withTimeoutOrNull(10_000L) { initialized.await() } != true) return@withLock
        if (closed) return@withLock
        speaking = true
        try {
            onSpeechStateChanged(true)
            withTimeoutOrNull(60_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val id = UUID.randomUUID().toString()
                    val completed = AtomicBoolean(false)
                    fun finish() {
                        if (completed.compareAndSet(false, true) && cont.isActive) cont.resume(Unit)
                    }
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { if (utteranceId == id) finish() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { if (utteranceId == id) finish() }
                    })
                    cont.invokeOnCancellation { completed.set(true); tts.stop() }
                    if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) finish()
                }
            }
        } finally {
            suppressUntil = SystemClock.elapsedRealtime() + 1_200L
            speaking = false
            onSpeechStateChanged(false)
        }
    }

    fun shutdown() { closed = true; initialized.complete(false); tts.stop(); tts.shutdown() }
}
