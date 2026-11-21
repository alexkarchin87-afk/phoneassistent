package com.voiceagent.oneplus13.lecture

import android.content.Context
import android.util.Log
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.LLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Records recognized text from the service and summarizes only after explicit approval. */
class LectureCommands(context: Context, llm: LLMClient, private val scope: CoroutineScope) {
    private val capture = LectureCapture()
    private val storage = LectureStorage(context.applicationContext)
    private val summarizer = LectureSummarizer(llm)

    fun start(title: String): Boolean = capture.start(title)
    fun isRecording(): Boolean = capture.isActive()
    fun appendTranscript(text: String) = capture.append(text)

    /** Called on an IO coroutine. Save raw text before making any network request. */
    fun stop(): Boolean {
        val session = capture.finish() ?: return false
        storage.save(session)
        scope.launch(Dispatchers.IO) {
            try {
                summarizer.summarize(session)
                storage.save(session)
                AgentEventBus.publish(AgentEvent.Reply("Конспект лекции готов."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("LectureCommands", "Lecture summary failed", error)
                AgentEventBus.publish(AgentEvent.Error("Конспект не создан; текст лекции сохранён.", error))
            }
        }
        return true
    }

    /** Best-effort local save on shutdown; never sends an unfinished recording. */
    fun preserveOnShutdown() {
        val session = capture.finish() ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { storage.save(session) }
                .onFailure { Log.e("LectureCommands", "Could not save unfinished lecture", it) }
        }
    }

    fun latestSummary(): String? = storage.latestSummary()
}
