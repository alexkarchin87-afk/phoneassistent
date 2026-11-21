package com.voiceagent.oneplus13.core

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Offline speech-to-text using Vosk. Runs fully on-device so lecture recording and
 * wake-word listening keep working without network.
 *
 * Model must be unpacked once (see [ensureModel]) from assets or downloaded into
 * app-internal storage; a small Russian model (~50MB) is recommended for this use case.
 */
class STTEngine(private val context: Context) {

    private var model: Model? = null
    private val modelLock = Any()
    @Volatile private var speechService: SpeechService? = null

    /** Called by the service's IO coroutine. Serialize asset sync across rapid restarts. */
    fun ensureModel(modelAssetPath: String = "model-ru-small") {
        val path = synchronized(assetLock) { StorageService.sync(context, modelAssetPath, "model") }
        synchronized(modelLock) {
            if (model == null) model = Model(path).also {
                check(it.pointer != null) { "Vosk could not open the speech model" }
            }
        }
    }

    private companion object {
        val assetLock = Any()
    }

    /** Delivers completed phrases while tracking speech-output overlap on each audio buffer. */
    fun listen(onStarted: () -> Unit = {}, acceptTranscript: () -> Boolean = { true }): Flow<String> = callbackFlow {
        val currentModel = synchronized(modelLock) { model }
            ?: throw IllegalStateException("Call ensureModel() first")
        val recognizer = FinalSpeechRecognizer(currentModel)
        val service = try {
            SpeechService(recognizer, 16000.0f)
        } catch (error: Exception) {
            recognizer.close()
            throw error
        }
        speechService = service

        val started = service.startListening(object : RecognitionListener {
            // If TTS overlapped an utterance, discard its entire final result even
            // if recognition finishes after the output gate has reopened.
            private var contaminated = false
            override fun onPartialResult(hypothesis: String?) {
                if (!acceptTranscript()) contaminated = true
            }

            override fun onResult(hypothesis: String?) {
                val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrDefault("") }.orEmpty()
                if (!acceptTranscript()) contaminated = true
                if (text.isNotBlank() && !contaminated) trySend(text)
                contaminated = false
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrDefault("") }.orEmpty()
                if (!acceptTranscript()) contaminated = true
                if (text.isNotBlank() && !contaminated) trySend(text)
                contaminated = false
            }

            override fun onError(exception: Exception?) {
                close(exception)
            }

            override fun onTimeout() { /* no-op: keep listening */ }
        })
        if (!started) {
            service.shutdown()
            recognizer.close()
            if (speechService === service) speechService = null
            throw IllegalStateException("Speech recognizer did not start")
        }
        if (!acceptTranscript()) service.setPause(true)
        onStarted()

        awaitClose {
            service.cancel()
            service.shutdown()
            recognizer.close()
            if (speechService === service) speechService = null
        }
    }.buffer(Channel.BUFFERED)

    /** Vosk continues reading audio while paused, but avoids recognition work. */
    fun setSpeechOutputActive(active: Boolean) {
        speechService?.let { service ->
            if (!active) service.reset()
            service.setPause(active)
        }
    }

    /** Only call after the listening coroutine has stopped. */
    fun releaseModel() {
        val old = synchronized(modelLock) {
            val existing = model
            model = null
            existing
        }
        old?.close()
    }
}

/** Vosk still decodes every buffer and calls onPartialResult for the TTS gate.
 * The app consumes only complete phrases, so it can omit the native draft calculation. */
internal class FinalSpeechRecognizer(model: Model) : Recognizer(model, 16000f) {
    override fun getPartialResult(): String = ""
}
