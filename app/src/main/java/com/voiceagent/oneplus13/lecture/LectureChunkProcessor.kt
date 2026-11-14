package com.voiceagent.oneplus13.lecture

/**
 * Cleans and appends raw STT output to a session. Kept separate from LectureCapture
 * so filtering rules (drop filler words, merge sentence fragments) can evolve without
 * touching the audio pipeline.
 */
class LectureChunkProcessor {

    private val fillerPatterns = listOf("э-э", "ну", "как бы", "типа").map {
        Regex("(?i)(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])")
    }
    private val whitespace = Regex("\\s+")

    fun process(session: LectureSession, rawChunk: String) {
        val cleaned = clean(rawChunk)
        if (cleaned.isNotBlank()) session.appendTranscriptChunk(cleaned)
    }

    private fun clean(text: String): String {
        var result = text.trim()
        fillerPatterns.forEach { pattern ->
            result = result.replace(pattern, "")
        }
        return result.replace(whitespace, " ").trim()
    }
}

\n