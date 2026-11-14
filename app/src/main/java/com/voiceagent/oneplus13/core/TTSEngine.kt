package com.voiceagent.oneplus13.core

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Serializes speech and exposes a gate for suppressing recognition of our own voice. */
class TTSEngine(context: Context) {
    @Volatile private var ready = false
    @Volatile private var speaking = false
    @Volatile private var suppressUntil = 0L
    private val speechMutex = Mutex()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init { tts.language = Locale("ru", "RU") }

    fun acceptsMicrophoneInput(): Boolean =
        !speaking && SystemClock.elapsedRealtime() >= suppressUntil

    suspend fun speak(text: String) = speechMutex.withLock {
        if (!ready || text.isBlank()) return@withLock
        speaking = true
        try {
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
        }
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
