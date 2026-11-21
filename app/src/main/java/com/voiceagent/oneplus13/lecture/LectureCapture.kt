package com.voiceagent.oneplus13.lecture

/** Owns only lecture state; audio is supplied by the service's single STT pipeline. */
internal class LectureCapture(private val processor: LectureChunkProcessor = LectureChunkProcessor()) {
    private var current: LectureSession? = null

    @Synchronized fun start(title: String): Boolean {
        if (current != null) return false
        current = LectureSession(title = title)
        return true
    }

    @Synchronized fun isActive(): Boolean = current != null

    @Synchronized fun append(text: String) {
        current?.let { processor.process(it, text) }
    }

    @Synchronized fun finish(): LectureSession? {
        val session = current ?: return null
        current = null
        session.endedAtMillis = System.currentTimeMillis()
        return session
    }
}
