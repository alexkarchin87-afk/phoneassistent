package com.voiceagent.oneplus13.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
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
    private var speechService: SpeechService? = null

    /** Unpacks the bundled Vosk model (assets/model-ru-small) into internal storage if needed. */
    fun ensureModel(modelAssetPath: String = "model-ru-small", onReady: (Model) -> Unit, onError: (Throwable) -> Unit) {
        StorageService.unpack(context, modelAssetPath, "model",
            { unpackedModel -> model = unpackedModel; onReady(unpackedModel) },
            { ex -> Log.e("STTEngine", "model unpack failed", ex); onError(ex) }
        )
    }

    /** Continuous partial+final recognition as a cold Flow; cancel the collector to stop listening. */
    fun listen(acceptTranscript: () -> Boolean = { true }): Flow<String> = callbackFlow {
        val currentModel = model ?: throw IllegalStateException("Call ensureModel() first")
        val recognizer = Recognizer(currentModel, 16000.0f)
        val service = SpeechService(recognizer, 16000.0f)
        speechService = service

        service.startListening(object : RecognitionListener {
            // If TTS overlapped an utterance, discard its entire final result even
            // if recognition finishes after the output gate has reopened.
            private var contaminated = false
            override fun onPartialResult(hypothesis: String?) {
                if (!acceptTranscript()) contaminated = true
            }

            override fun onResult(hypothesis: String?) {
                val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
                if (!acceptTranscript()) contaminated = true
                if (text.isNotBlank() && !contaminated) trySend(text)
                contaminated = false
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = hypothesis?.let { JSONObject(it).optString("text") }.orEmpty()
                if (!acceptTranscript()) contaminated = true
                if (text.isNotBlank() && !contaminated) trySend(text)
                contaminated = false
            }

            override fun onError(exception: Exception?) {
                close(exception)
            }

            override fun onTimeout() { /* no-op: keep listening */ }
        })

        awaitClose {
            service.stop()
            service.shutdown()
        }
    }.buffer(0)

    fun stop() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }
}

